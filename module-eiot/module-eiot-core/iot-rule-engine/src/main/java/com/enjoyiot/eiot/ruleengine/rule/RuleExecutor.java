/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot]
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */
package com.enjoyiot.eiot.ruleengine.rule;


import com.enjoyiot.eiot.IRuleLogData;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.action.Action;
import com.enjoyiot.eiot.ruleengine.action.alert.AlertAction;
import com.enjoyiot.eiot.ruleengine.filter.Filter;
import com.enjoyiot.eiot.ruleengine.listener.Listener;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.rule.dto.RuleLog;
import com.enjoyiot.module.eiot.api.rule.dto.TriggerOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 【规则执行器】- 规则引擎的执行引擎，实现四道关卡匹配机制
 *
 * 每条规则匹配消息时，需依次通过以下四道关卡：
 *
 * 【四道关卡执行流程】
 *
 *  消息到达
 *    ↓
 *  ┌─────────────────────────────────────────────────────┐
 *  │ 第1关：Listener（监听器匹配）                        │
 *  │  - 判断消息是否来自规则关注的设备/产品                │
 *  │  - 多个 Listener 是 OR 关系（任一匹配即通过）         │
 *  │  - 不匹配 → 检查是否需要告警解除 → 结束               │
 *  └─────────────────────────────────────────────────────┘
 *    ↓ 通过
 *  ┌─────────────────────────────────────────────────────┐
 *  │ 第2关：Filter（过滤器匹配）                          │
 *  │  - 对已触发的消息做条件判断（如温度 > 30°C）          │
 *  │  - 多个 Filter 是 AND 关系（全部满足才通过）          │
 *  │  - 不匹配 → 记录日志 + 检查是否需要告警解除 → 结束    │
 *  └─────────────────────────────────────────────────────┘
 *    ↓ 通过
 *  ┌─────────────────────────────────────────────────────┐
 *  │ 第3关：RateLimit（限频判断）                         │
 *  │  - 基于 Redisson RRateLimiter 实现                   │
 *  │  - 通过 minIntervalSec 控制最小触发间隔              │
 *  │  - 不通过 → 跳过本次执行 → 结束                      │
 *  └─────────────────────────────────────────────────────┘
 *    ↓ 通过
 *  ┌─────────────────────────────────────────────────────┐
 *  │ 第4关：Action（动作执行）                            │
 *  │  - 有延时 → 入 Redisson 延时队列，到期后执行          │
 *  │  - 无延时 → 直接执行所有 Action                       │
 *  │  - 记录执行日志到数据库                               │
 *  └─────────────────────────────────────────────────────┘
 *
 * 【告警解除机制】
 *  当规则配置了 enableAlertRecover=true 时：
 *  - Listener 或 Filter 不匹配时，如果之前处于告警状态，会调度恢复任务
 *  - 恢复任务在 recoverQuietSec 秒后执行，执行 AlertAction.recover()
 *  - 通过 recoverToken 机制防止旧恢复任务误执行
 */
@Component
@Slf4j
public class RuleExecutor {

    /** 规则日志数据接口（记录规则执行日志到数据库） */
    @Lazy
    @Autowired
    private IRuleLogData ruleLogData;

    /** 触发控制服务（限频、延时、告警解除） */
    @Autowired
    private TriggerControlService triggerControlService;

    /**
     * 【规则执行入口】- 四道关卡依次匹配
     *
     * @param message 物模型消息
     * @param rule    匹配到的规则
     */
    /**
     * 【规则执行入口】- 四道关卡依次匹配
     *
     * 这是规则引擎最核心的执行方法。每条从 RuleMessageHandler 匹配到的规则，
     * 都需要依次通过这四道关卡的检查，全部通过后才执行动作。
     *
     * 四道关卡：
     *   第1关 Listener (OR)  — 粗筛：消息是否来自规则关注的设备/产品？
     *   第2关 Filter (AND)   — 精筛：设备当前状态是否满足条件（如温度 > 30）？
     *   第3关 RateLimit       — 限频：是否超过最小触发间隔？
     *   第4关 Action          — 执行：延时或立即执行配置的动作
     *
     * 告警解除机制：当 Listener 或 Filter 不匹配时，如果规则开启了 enableAlertRecover，
     * 会调度一个延时恢复任务（在 recoverQuietSec 秒后执行 AlertAction.recover()）。
     * 如果恢复任务到期前规则又被重新触发，则通过 cancelRecover 取消旧的恢复任务。
     *
     * @param message 物模型消息（来自设备上报，包含 type/identifier/data/deviceId 等）
     * @param rule    从内存索引中匹配到的规则对象
     */
    public void execute(ThingModelMessage message, Rule rule) {
        // 获取触发控制配置（minIntervalSec / delaySec / enableAlertRecover / recoverQuietSec）
        TriggerOptions options = rule.getTriggerOptions();

        // ====================================================================
        // 第1关：Listener 匹配 — OR 关系，任一匹配即通过
        // ====================================================================
        // 判断"要不要处理这条消息"（基于消息本身的元数据）
        // 如：消息类型是否是 property？identifier 是否是 report？设备 pk 是否匹配？
        if (!doListeners(message, rule)) {
            log.info("The listener did not match the appropriate content,rule:{},{}", rule.getId(), rule.getName());
            // Listener 不匹配 → 说明告警条件可能已消失
            // 如果规则开启了告警解除，尝试调度恢复任务（延迟 recoverQuietSec 秒后执行恢复通知）
            triggerControlService.scheduleRecoverIfNeeded(rule, message, options);
            return; // 不满足监听条件，停止执行
        }

        // ====================================================================
        // 第2关：Filter 匹配 — AND 关系，全部满足才通过
        // ====================================================================
        // 判断"消息内容是否满足条件"（基于设备当前状态）
        // 如：设备当前温度是否 > 30？湿度是否 < 80？设备是否在线？
        if (!doFilters(rule, message)) {
            // 记录"过滤器不匹配"的执行日志（用于后续排查规则为何没触发）
            recordLog(rule.getId(), RuleLog.STATE_UNMATCHED_FILTER, null, false);
            // Filter 不匹配 → 同样调度告警恢复任务
            triggerControlService.scheduleRecoverIfNeeded(rule, message, options);
            return; // 不满足过滤条件，停止执行
        }

        // ====================================================================
        // 匹配成功！取消之前的告警恢复任务
        // ====================================================================
        // Listener + Filter 都匹配成功 → 规则被触发了，说明告警条件又满足了
        // 此时需要通过 cancelRecover 刷新 recoverToken，使之前排队的恢复任务作废
        // 这防止了"温度下降后又上升"场景下旧的恢复通知误发
        triggerControlService.cancelRecover(rule.getId());

        // ====================================================================
        // 第3关：RateLimit 限频
        // ====================================================================
        // 基于 Redisson RRateLimiter（令牌桶算法），控制规则的最小触发间隔
        // 如果 minIntervalSec=60，则 60 秒内只允许触发 1 次，防止告警风暴
        if (!triggerControlService.passRateLimit(rule.getId(), options)) {
            log.info("rule {} skipped by minIntervalSec", rule.getId());
            return; // 被限频拦截，跳过本次执行
        }

        // ====================================================================
        // 第4关：Action 执行
        // ====================================================================
        // 全部关卡通过 → 执行动作
        // 如果配置了 delaySec > 0 → 入 Redisson 延时队列，到期后执行
        // 如果没有配置延时 → 立即执行所有 Action
        // 执行完成后会记录规则执行日志到数据库
        triggerControlService.executeAction(rule, message, options, this);
    }

    /**
     * 【第1关：监听器匹配】- OR 关系，任一 Listener 匹配即通过
     *
     * 每个 Listener 对应一个设备/产品条件：
     *  - DeviceListener：匹配 productKey + deviceName + messageType + identifier + 参数条件
     *  - 多个 Listener 中只要有一个返回 true，就算通过
     */
    private boolean doListeners(ThingModelMessage message, Rule rule) {
        List<Listener<?>> listeners = rule.getListeners();
        for (Listener<?> listener : listeners) {
            if (listener.execute(message)) {
                // 只要有一个监听器匹配到数据即可
                return true;
            }
        }
        return false;
    }

    /**
     * 【第2关：过滤器匹配】
     *
     * 根据 lsnCond 配置决定过滤器的匹配策略：
     *  - 1=任意满足(OR)：任一 Filter 满足即通过
     *  - 2=全部满足(AND, 默认)：所有 Filter 必须全部通过
     *  - 3=不满足(NOT)：所有 Filter 都不满足才算通过（翻转逻辑）
     */
    private boolean doFilters(Rule rule, ThingModelMessage msg) {
        List<Filter<?>> filters = rule.getFilters();
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        // 获取执行条件配置，默认全部满足(AND)
        int lsnCond = 2;
        if (rule.getTriggerOptions() != null && rule.getTriggerOptions().getLsnCond() != null) {
            lsnCond = rule.getTriggerOptions().getLsnCond();
        }
        if (lsnCond == 1) {
            // 任意满足(OR)：只要有一个过滤器通过就通过
            for (Filter<?> filter : filters) {
                if (filter.execute(msg)) {
                    return true;
                }
            }
            return false;
        } else if (lsnCond == 3) {
            // 不满足(NOT)：所有过滤器都不满足才通过
            for (Filter<?> filter : filters) {
                if (filter.execute(msg)) {
                    return false;
                }
            }
            return true;
        }
        // 默认：全部满足(AND)
        for (Filter<?> filter : filters) {
            if (!filter.execute(msg)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 【执行动作】- 被延时队列回调或直接调用
     *
     * 执行所有 Action，并记录执行日志。
     *
     * @param rule     规则对象
     * @param message  物模型消息
     * @param recovery true=告警恢复, false=正常触发
     */
    void executeActions(Rule rule, ThingModelMessage message, boolean recovery) {
        RuleLog ruleLog = new RuleLog();
        ruleLog.setRuleId(rule.getId());
        ruleLog.setState(recovery ? RuleLog.STATE_RECOVERED : RuleLog.STATE_MATCHED_FILTER);
        try {
            // 执行所有 Action
            List<String> results = doActions(rule, message, recovery);
            ruleLog.setContent(JsonUtils.toJsonString(results));
            if (!recovery) {
                ruleLog.setState(RuleLog.STATE_EXECUTED_ACTION);
            }
            ruleLog.setSuccess(true);
            // 状态标记：恢复时标记恢复，触发时由调用方标记（避免重复）
            if (recovery) {
                triggerControlService.markRecovered(rule.getId());
                log.info("rule {} alert recovered", rule.getId());
            } else {
                log.info("rule execution completed,id:{}", rule.getId());
            }
        } catch (Throwable e) {
            log.error("rule execution error,id:" + rule.getId(), e);
            ruleLog.setSuccess(false);
            ruleLog.setContent(e.toString());
        } finally {
            ruleLog.setLogAt(System.currentTimeMillis());
            // 记录规则执行日志到数据库
            ruleLogData.add(ruleLog);
        }
    }

    /**
     * 【遍历执行所有 Action】
     *
     * 正常触发：执行所有 Action.execute()
     * 告警恢复：只执行 AlertAction.recover()，其他 Action 跳过
     */
    private List<String> doActions(Rule rule, ThingModelMessage msg, boolean recovery) {
        List<String> results = new ArrayList<>();
        for (Action<?> action : rule.getActions()) {
            if (recovery) {
                // 恢复模式：只执行告警动作的恢复逻辑
                if (action instanceof AlertAction) {
                    results.addAll(((AlertAction) action).recover(msg));
                }
                continue;
            }
            // 正常模式：执行所有动作
            results.addAll(action.execute(msg));
        }
        return results;
    }

    /**
     * 【记录规则执行日志】- 写入数据库
     *
     * @param ruleId  规则 ID
     * @param state   执行状态（MATCHED_FILTER / UNMATCHED_FILTER / EXECUTED_ACTION / RECOVERED）
     * @param content 执行结果内容
     * @param success 是否成功
     */
    private void recordLog(Long ruleId, String state, String content, Boolean success) {
        RuleLog ruleLog = new RuleLog();
        ruleLog.setRuleId(ruleId);
        ruleLog.setState(state);
        ruleLog.setContent(content);
        ruleLog.setSuccess(success);
        ruleLog.setLogAt(System.currentTimeMillis());
        ruleLogData.add(ruleLog);
    }

}
