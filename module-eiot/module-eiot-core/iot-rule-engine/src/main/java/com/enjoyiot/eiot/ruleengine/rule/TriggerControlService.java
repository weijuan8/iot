package com.enjoyiot.eiot.ruleengine.rule;

import com.alibaba.ttl.TtlRunnable;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.module.eiot.api.rule.dto.TriggerOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RMap;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 【触发控制服务】— 限频、延时执行、告警解除（基于 Redisson）
 *
 * <h2>三大职责</h2>
 * <table>
 *   <tr><th>职责</th><th>Redisson 机制</th><th>Redis Key</th></tr>
 *   <tr><td>限频 (RateLimit)</td><td>RRateLimiter</td>
 *       <td>rule:trigger:limit:{ruleId}</td></tr>
 *   <tr><td>延时执行</td><td>RDelayedQueue → RBlockingQueue</td>
 *       <td>rule:trigger:action:q</td></tr>
 *   <tr><td>告警解除</td><td>RDelayedQueue + token 校验</td>
 *       <td>rule:trigger:recover:q + rule:trigger:state</td></tr>
 * </table>
 *
 * <h2>延时队列消费</h2>
 * 启动时创建 2 个后台线程，分别消费 actionQueue 和 recoverQueue。
 * 使用 TTL 线程池保证租户上下文传递。
 *
 * <h2>告警解除 Token 机制</h2>
 * <pre>
 * 场景：温度超过30度 → 触发告警 → 10秒后温度降到25度 → 调度恢复
 *
 * T=0s:  温度35，触发告警 → state.alerting=true, state.recoverToken=null
 * T=5s:  温度28，Listener不匹配 → scheduleRecoverIfNeeded
 *        → 生成 token=AAA, state.recoverToken=AAA
 *        → 入 recoverQueue 延迟 recoverQuietSec 秒
 * T=8s:  温度又升到33，再次触发告警
 *        → cancelRecover: state.recoverToken=BBB (旧 token AAA 作废)
 * T=15s: AAA token 的 job 到期
 *        → 校验: job.token(AAA) != state.recoverToken(BBB) → 跳过！
 *        这样旧的恢复任务不会误执行。
 * </pre>
 *
 * @see RuleExecutor 调用方
 * @see TriggerState 状态记录
 * @see TriggerJob 队列任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerControlService implements InitializingBean {

    // ========================================================================
    // Redis Key 常量
    // ========================================================================

    /** 规则触发状态: RMap<ruleId, TriggerState JSON> */
    private static final String STATE_KEY = "rule:trigger:state";
    /** 动作延时队列 */
    private static final String ACTION_QUEUE = "rule:trigger:action:q";
    /** 告警恢复延时队列 */
    private static final String RECOVER_QUEUE = "rule:trigger:recover:q";
    /** 限流器 Key 前缀: rule:trigger:limit:{ruleId} */
    private static final String RATE_LIMITER_KEY_PREFIX = "rule:trigger:limit:";

    private final RedissonClient redissonClient;
    private final RuleManager ruleManager;
    private final RuleExecutor ruleExecutor;

    // ========================================================================
    // 延时队列基础设施
    // ========================================================================

    /** 动作阻塞队列 (消费者阻塞等待) */
    private RBlockingQueue<TriggerJob> actionQueue;
    /** 动作延时队列 (生产者 offer 时指定延迟) */
    private RDelayedQueue<TriggerJob> actionDelayedQueue;
    /** 恢复阻塞队列 */
    private RBlockingQueue<TriggerJob> recoverQueue;
    /** 恢复延时队列 */
    private RDelayedQueue<TriggerJob> recoverDelayedQueue;
    /** 消费者线程池 (2 个线程: action + recover) */
    private ExecutorService consumerPool;

    /**
     * 【初始化】— Bean 创建后自动调用
     * 初始化延时队列 + 启动 2 个消费者线程。
     */
    @Override
    public void afterPropertiesSet() {
        // 创建队列: Redisson DelayedQueue 绑定到 BlockingQueue
        actionQueue = redissonClient.getBlockingQueue(ACTION_QUEUE);
        actionDelayedQueue = redissonClient.getDelayedQueue(actionQueue);
        recoverQueue = redissonClient.getBlockingQueue(RECOVER_QUEUE);
        recoverDelayedQueue = redissonClient.getDelayedQueue(recoverQueue);

        // 启动消费者线程 (TTL 包装，保证租户上下文在线程间传递)
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("rule-trigger-consumer");
            return t;
        });
        consumerPool = TtlExecutors.getTtlExecutorService(executor);
        consumerPool.submit(() -> consume(actionQueue, false));   // 线程1: 动作消费
        consumerPool.submit(() -> consume(recoverQueue, true));   // 线程2: 恢复消费
    }

    // ========================================================================
    // 延时队列消费
    // ========================================================================

    /**
     * 消费延时队列。
     * 循环 poll(1秒超时) → 获取 job → 反序列化消息 → 执行。
     *
     * @param queue    阻塞队列
     * @param recovery true=恢复队列, false=动作队列
     */
    private void consume(RBlockingQueue<TriggerJob> queue, boolean recovery) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TriggerJob job = queue.poll(1, TimeUnit.SECONDS);
                if (job == null) continue;

                TenantContextHolder.setTenantId(job.getTenantId());
                processJob(job, recovery);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (org.redisson.RedissonShutdownException e) {
                log.info("Redisson is shutdown, stop trigger queue consumer. recovery={}", recovery);
                break;
            } catch (Throwable e) {
                log.error("Trigger queue consumer error, recovery={}", recovery, e);
            }
        }
    }

    /**
     * 处理队列中的任务。
     *
     * <p>恢复任务特殊处理：
     * <ol>
     *   <li>Token 校验: 当前 Redis state.recoverToken == job.recoverToken?
     *       不匹配 → 说明期间又触发了新告警，跳过</li>
     *   <li>状态检查: state.isAlerting()? 不是 alerting 则不恢复</li>
     *   <li>执行恢复动作 → markRecovered</li>
     * </ol>
     */
    private void processJob(TriggerJob job, boolean recoveryJob) {
        // 规则可能已被删除
        Rule rule = ruleManager.getRule(job.getRuleId());
        if (rule == null) {
            log.warn("rule {} not found in cache, skip {} job",
                    job.getRuleId(), recoveryJob ? "recover" : "action");
            return;
        }
        ThingModelMessage msg = JsonUtils.parseObject(job.getMessageJson(), ThingModelMessage.class);

        if (recoveryJob) {
            // === 告警恢复任务 ===
            TriggerState state = getState(rule.getId());
            // 状态检查: 必须处于告警状态才能恢复
            if (state == null || !state.isAlerting()) {
                log.info("rule {} recover skipped, not alerting", rule.getId());
                return;
            }
            // Token 校验: 防止旧恢复任务误执行
            if (job.getRecoverToken() != null
                    && !job.getRecoverToken().equals(state.getRecoverToken())) {
                log.info("rule {} recover skipped, token mismatch", rule.getId());
                return;
            }
            ruleExecutor.executeActions(rule, msg, true);
        } else {
            // === 普通延时动作 ===
            ruleExecutor.executeActions(rule, msg, false);
            markTriggered(rule.getId());
        }
    }

    // ========================================================================
    // 限频 (RateLimit)
    // ========================================================================

    /**
     * 限频检查。
     * 使用 Redisson RRateLimiter (OVERALL 模式)，
     * 每 minIntervalSec 秒最多允许 1 次。
     *
     * @return true=通过限频, false=被限频
     */
    public boolean passRateLimit(Long ruleId, TriggerOptions options) {
        Integer minIntervalSec = options == null ? null : options.getMinIntervalSec();
        if (minIntervalSec == null || minIntervalSec <= 0) {
            return true; // 不限频
        }
        RRateLimiter limiter = redissonClient.getRateLimiter(RATE_LIMITER_KEY_PREFIX + ruleId);
        // trySetRate: 如果首次设置则生效，已存在则忽略
        limiter.trySetRate(RateType.OVERALL, 1, minIntervalSec, RateIntervalUnit.SECONDS);
        return limiter.tryAcquire();
    }

    // ========================================================================
    // 动作执行 (延时/立即)
    // ========================================================================

    /**
     * 执行动作：有延时则入延时队列，无延时则直接执行。
     *
     * @param rule    规则对象
     * @param msg     物模型消息
     * @param options 触发选项 (含 delaySec)
     * @param executor 规则执行器
     */
    public void executeAction(Rule rule, ThingModelMessage msg,
                               TriggerOptions options, RuleExecutor executor) {
        long delayMs = options != null && options.getDelaySec() != null
                && options.getDelaySec() > 0
                ? options.getDelaySec() * 1000L : 0L;
        if (delayMs > 0) {
            // 延时执行: 入 Redisson DelayedQueue
            TriggerJob job = new TriggerJob(rule.getId(),
                    JsonUtils.toJsonString(msg), false, null, rule.getTenantId());
            actionDelayedQueue.offer(job, delayMs, TimeUnit.MILLISECONDS);
        } else {
            // 直接执行
            executor.executeActions(rule, msg, false);
            markTriggered(rule.getId());
        }
    }

    // ========================================================================
    // 告警解除 (Recover)
    // ========================================================================

    /**
     * 调度告警解除任务（如果需要）。
     *
     * <p>触发时机：Listener 或 Filter 不匹配时调用。
     *
     * <p>前置条件（任一不满足则跳过）：
     * <ul>
     *   <li>enableAlertRecover == true</li>
     *   <li>当前规则处于 alerting 状态</li>
     * </ul>
     *
     * <p>执行逻辑：
     * <ol>
     *   <li>生成新的 recoverToken (UUID)</li>
     *   <li>更新 Redis state.recoverToken</li>
     *   <li>入 recoverQueue 延迟 recoverQuietSec 秒</li>
     * </ol>
     */
    public void scheduleRecoverIfNeeded(Rule rule, ThingModelMessage msg,
                                         TriggerOptions options) {
        if (options == null || options.getEnableAlertRecover() == null
                || !options.getEnableAlertRecover()) {
            return;
        }
        TriggerState state = getState(rule.getId());
        if (state == null || !state.isAlerting()) {
            return; // 不是告警状态，不需要恢复
        }
        long delayMs = options.getRecoverQuietSec() != null
                && options.getRecoverQuietSec() > 0
                ? options.getRecoverQuietSec() * 1000L : 0L;
        // 生成新 token → 旧恢复任务作废
        String token = UUID.randomUUID().toString();
        state.setRecoverToken(token);
        saveState(rule.getId(), state);
        if (delayMs > 0) {
            TriggerJob job = new TriggerJob(rule.getId(),
                    JsonUtils.toJsonString(msg), true, token, rule.getTenantId());
            recoverDelayedQueue.offer(job, delayMs, TimeUnit.MILLISECONDS);
        } else {
            ruleExecutor.executeActions(rule, msg, true);
        }
    }

    /**
     * 取消恢复任务。
     * 通过重置 token 使已投递的恢复任务"作废"。
     * 当规则再次被触发时调用。
     */
    public void cancelRecover(Long ruleId) {
        TriggerState state = getState(ruleId);
        if (state != null) {
            state.setRecoverToken(UUID.randomUUID().toString());
            saveState(ruleId, state);
        }
    }

    // ========================================================================
    // 状态管理 (Redis)
    // ========================================================================

    /** 标记规则已触发（进入告警状态） */
    public void markTriggered(Long ruleId) {
        TriggerState state = getState(ruleId);
        if (state == null) state = new TriggerState();
        state.setAlerting(true);
        state.setRecoverToken(null);  // 新告警 → 清空旧恢复 token
        saveState(ruleId, state);
    }

    /** 标记规则已恢复（退出告警状态） */
    public void markRecovered(Long ruleId) {
        TriggerState state = getState(ruleId);
        if (state == null) state = new TriggerState();
        state.setAlerting(false);
        state.setRecoverToken(null);
        saveState(ruleId, state);
    }

    /** 从 Redis 读取规则触发状态 */
    private TriggerState getState(Long ruleId) {
        RMap<Long, String> map = redissonClient.getMap(STATE_KEY);
        String json = map.get(ruleId);
        if (json == null) return null;
        return JsonUtils.parseObject(json, TriggerState.class);
    }

    /** 保存规则触发状态到 Redis */
    private void saveState(Long ruleId, TriggerState state) {
        RMap<Long, String> map = redissonClient.getMap(STATE_KEY);
        map.put(ruleId, JsonUtils.toJsonString(state));
    }

    /**
     * 清理规则的 Redis 缓存（规则删除时调用）。
     * 清理：限流器 + 触发状态
     */
    public void clearRuleCache(Long ruleId) {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(RATE_LIMITER_KEY_PREFIX + ruleId);
            limiter.delete();
            RMap<Long, String> map = redissonClient.getMap(STATE_KEY);
            map.remove(ruleId);
        } catch (Throwable e) {
            log.warn("clear rule cache failed, ruleId: {}", ruleId, e);
        }
    }
}
