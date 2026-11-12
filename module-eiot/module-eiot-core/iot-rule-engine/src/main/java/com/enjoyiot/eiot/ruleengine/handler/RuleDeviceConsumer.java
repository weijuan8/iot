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
package com.enjoyiot.eiot.ruleengine.handler;


import com.enjoyiot.eiot.common.constant.Constants;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.message.core.ConsumerHandler;
import com.enjoyiot.eiot.message.core.MqConsumer;
import com.enjoyiot.framework.common.util.thread.ThreadUtil;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import com.google.common.collect.Lists;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.Order;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 【规则引擎消费者总调度器】- 设备消息进入规则引擎的统一入口
 *
 * 该类是整个 Enjoy-IoT 平台「消费者模块」的核心，负责从消息队列接收 ThingModelMessage，
 * 然后按照 @Order 注解定义的顺序，依次将消息分发给所有 DeviceMessageHandler 处理。
 *
 * 【架构定位】
 *  RuleDeviceConsumer 是上行消息处理链的"总控"：
 *  设备上报 → EmqxVerticle(协议解析) → ThingComponent.report()(模型转换)
 *    → sendMessage()(发布到 device_thing topic) → RuleDeviceConsumer.handler()(消费入口)
 *      → 依次调用所有 DeviceMessageHandler
 *
 * 【消息消费方式】
 *  通过 MqConsumer.consume(topic, this) 订阅 Constants.THING_MODEL_MESSAGE_TOPIC("device_thing")。
 *  底层消息队列实现默认为 Spring Event（进程内），可切换为 RocketMQ/Kafka。
 *
 * 【Handler 执行链（按 @Order 排序）】
 *  1. DeviceMessageLogHandler    → 设备消息日志入库（TDengine 时序库）
 *  2. DevicePropertyHandler      → 属性上报处理：物模型校验 + 写入 Redis 缓存 + 写入时序库
 *  3. DeviceStateCheckHandler    → 设备状态检查：在线/离线状态更新 + 子设备状态同步
 *  4. DeviceConfigHandler        → 设备配置回复：收到配置获取请求时，回复配置信息给设备
 *  5. RuleMessageHandler         → 规则引擎匹配：内存索引匹配规则 → RuleExecutor 四道关卡执行
 *
 * 【异步执行模式】
 *  每个 handler 通过 CompletableFuture.thenRunAsync() 在独立线程池中异步执行，
 *  但通过 future.join() 确保所有 handler 按顺序执行（前一个完成后才启动下一个）。
 *  这保证了"先入库日志 → 再校验属性 → 再检查状态 → 最后触发规则"的顺序。
 *
 * @see DeviceMessageHandler 处理器接口
 * @see RuleConfiguration Spring Bean 注册配置
 */
@Slf4j
public class RuleDeviceConsumer implements ConsumerHandler<ThingModelMessage>, ApplicationContextAware {

    /**
     * 【Handler 列表】- 按 @Order 排序后的处理器链
     * 从 Spring 容器中自动发现所有 DeviceMessageHandler 实现类，
     * 按 @Order 注解值升序排列（Order 值越小越先执行）
     */
    private final List<DeviceMessageHandler> handlers = new ArrayList<>();

    /**
     * 【Handler 线程池】- 异步执行 handler 的线程池
     * 线程数 = handler 数量 × 2，线程名前缀为 "messageHandler"
     */
    private ScheduledThreadPoolExecutor messageHandlerPool;

    /**
     * 【构造函数】- 订阅消息队列，注册本类为消费者
     *
     * @param consumer MQ 消费者实例（Spring Event / RocketMQ / Kafka 自动适配）
     */
    @SneakyThrows
    public RuleDeviceConsumer(MqConsumer<ThingModelMessage> consumer) {
        // 订阅 "device_thing" topic，当有设备消息到达时回调 this.handler()
        consumer.consume(Constants.THING_MODEL_MESSAGE_TOPIC, this);
    }

    /**
     * 【Spring 容器回调】- 自动发现并排序所有 DeviceMessageHandler 实现
     *
     * 执行步骤：
     *  1. 从 ApplicationContext 中获取所有 DeviceMessageHandler 类型的 Bean
     *  2. 按 @Order 注解值升序排列（Order 值越小优先级越高）
     *     - 无 @Order 注解的排到最后（Integer.MAX_VALUE）
     *  3. 初始化线程池（线程数 = handler 数量 × 2）
     *
     * 例如：DeviceMessageLogHandler(@Order=1) → DevicePropertyHandler(@Order=2)
     *       → DeviceStateCheckHandler(@Order=3) → DeviceConfigHandler(@Order=4)
     *       → RuleMessageHandler(@Order=5)
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        // 从 Spring 容器获取所有 DeviceMessageHandler Bean
        Map<String, DeviceMessageHandler> handlerMap = applicationContext.getBeansOfType(DeviceMessageHandler.class);
        // 初始化线程池，线程数为 handler 数量的 2 倍
        messageHandlerPool = ThreadUtil.newScheduled(handlerMap.size() * 2, "messageHandler");
        List<DeviceMessageHandler> handlerList = Lists.newArrayList(handlerMap.values());
        // 按 @Order 注解排序：Order 值越小越先执行
        handlerList = handlerList.stream().sorted(Comparator.comparingInt(a -> {
            Order o = a.getClass().getAnnotation(Order.class);
            if (o == null) {
                return Integer.MAX_VALUE; // 无注解的排最后
            }
            return o.value();
        })).collect(Collectors.toList());
        handlers.addAll(handlerList);
    }

    /**
     * 【消息消费入口】- MQ 回调方法，设备消息到达时触发
     *
     * 处理流程：
     *  1. 打印消息日志
     *  2. 通过 CompletableFuture 链式调用所有 handler
     *     - 每个 handler 在独立线程池中异步执行
     *     - future.join() 确保前一个 handler 完成后才启动下一个
     *     - 保证执行顺序：日志入库 → 属性处理 → 状态检查 → 配置回复 → 规则匹配
     *  3. 任一 handler 抛异常不影响后续 handler 执行（异常被捕获并记录日志）
     *
     * @param msg 物模型消息（来自设备上报或平台下发）
     */
    @SneakyThrows
    @Override
    public void handler(ThingModelMessage msg) {
        log.info("received thing model message:{}", msg);
        try {
            // 创建 CompletableFuture 链，确保 handler 按顺序异步执行
            CompletableFuture<?> future = CompletableFuture.completedFuture("messageHandler");
            for (DeviceMessageHandler handler : this.handlers) {
                // thenRunAsync：在上一个任务完成后，在指定线程池中异步执行下一个 handler
                future = future.thenRunAsync(() -> {
                    try {
                        extracted(msg, handler);
                    } catch (Throwable e) {
                        // 单个 handler 失败不影响后续 handler
                        log.error("handler message error", e);
                    }
                }, messageHandlerPool);
            }
            future.join(); // 阻塞等待所有 handler 执行完成
        } catch (Throwable e) {
            log.error("rule device message process error", e);
        }
    }

    /**
     * 【Handler 执行封装】- 确保 data 字段不为 null，并在租户上下文中执行
     *
     * 安全处理：
     *  1. 如果 msg.getData() 不是 Map 类型（可能为 null），初始化为空 HashMap
     *     （避免 handler 内部 NPE）
     *  2. 通过 TenantUtils.executeIgnore() 在租户上下文中执行
     *     （忽略租户隔离，确保系统级 handler 能正常处理所有租户的消息）
     *
     * @param msg     物模型消息
     * @param handler 要执行的处理器
     */
    private static void extracted(ThingModelMessage msg, DeviceMessageHandler handler) {
        if (!(msg.getData() instanceof Map)) {
            msg.setData(new HashMap<>());
        }
        TenantUtils.executeIgnore(() -> {
                    handler.handle(msg);
                }
        );
    }

}

