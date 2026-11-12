
/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com | Tel: 19918996474
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot] | Tel: 19918996474
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
package com.enjoyiot.eiot.component.core;

import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.common.constant.Constants;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.common.thing.ComponentMessage;
import com.enjoyiot.eiot.message.core.ConsumerHandler;
import lombok.SneakyThrows;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;

import static com.enjoyiot.eiot.common.constant.Constants.*;

/**
 * 【组件抽象基类】- IoT 组件的核心实现，负责消息路由和设备管理
 *
 * ============================================================================
 * 【架构位置：整个组件系统的"引擎层"】
 * 该类是连接「组件实例」与「平台基础设施」的桥梁。
 * 所有协议组件（MQTT、TCP、CoAP 等）都继承此类，复用其消息路由能力。
 *
 * 【继承链】
 *  Component (接口)
 *    → AbstractComponent (本类 - 消息路由 + 组件发现)
 *      → ThingComponent (配置轮询 + 物模型消息分发)
 *        → EmqxComponent / MqttComponent / TcpComponent / ... (具体协议实现)
 *
 * ============================================================================
 * 【1. 组件发现与注册机制】
 *
 *   注册流程时序图：
 *   ┌─────────────┐        ┌──────────────────┐        ┌──────────────┐
 *   │ 组件实例     │        │ ComponentManager  │        │ 数据库        │
 *   │ (本类)      │        │ (平台管理端)       │        │ eiot_component│
 *   └──────┬──────┘        └────────┬─────────┘        └──────┬───────┘
 *          │ 1.每3秒发送              │                        │
 *          │ COMPONENT_DISCOVER_TOPIC│                        │
 *          │────────────────────────>│                        │
 *          │                         │ 2.检查组件类型是否已入库 │
 *          │                         │───────────────────────>│
 *          │                         │ 3.首次发现→insert status=0│
 *          │                         │<───────────────────────│
 *          │ 4.回复                  │                        │
 *          │ COMPONENT_DISCOVER_REPLY│                        │
 *          │<────────────────────────│                        │
 *          │ 5.匹配componentId       │                        │
 *          │ 6.订阅{type}_device_thing topic                  │
 *          │======================== │                        │
 *
 *   为什么用"心跳发现"模式？
 *   - 组件动态启动/停止，平台无需硬编码组件列表
 *   - 支持同类型多实例（水平扩展），每个实例有独立 componentId
 *   - 组件异常退出后心跳停止，可被监控感知
 *
 * ============================================================================
 * 【2. 消息路由机制（核心）】
 *
 *   上行消息（设备 → 平台）：
 *    设备通过协议上报数据
 *      → 组件接收后调用 sendMessage()
 *        → ① 写入 Redis：str:iot:device:router:{pk}:{dn} = {type}_{componentId}
 *        → ② 发布到 device_thing topic（所有组件都会消费）
 *          → 每个组件的 handler() 被回调
 *            → 从 Redis 查路由：这个设备是不是我的？
 *              → 是 → onMessage() → 上报到规则引擎
 *              → 否 → 忽略
 *
 *   下行消息（平台 → 设备）：
 *    平台 Controller 接收用户指令
 *      → DeviceCtrlServiceImpl.send()
 *        → ComponentManager.sendToDevice()
 *          → 从 Redis 查路由：设备在哪个组件上？
 *            → 发布到 {componentType}_device_thing topic
 *              → 对应组件的 handler() 被回调
 *                → 从 Redis 查路由确认（二次确认）
 *                  → onMessage() → 协议方法 → 发给设备
 *
 *   Redis 路由 Key 格式：str:iot:device:router:{productKey}:{deviceName}
 *   Redis 路由 Value 格式：{componentType}_{componentId}
 *   例如：str:iot:device:router:prod_001:dev_001 → mqtt_a1b2c3d4
 *
 * ============================================================================
 * 【3. 设备路由缓存】
 *   cacheDeviceComponentInfo() 在以下时机被调用：
 *   - 设备通过认证时（TCP/Modbus 注册包通过）
 *   - 设备首次上报数据时（sendMessage 内部自动缓存）
 *
 * ============================================================================
 * 【4. 消费者模式】
 *   实现 ConsumerHandler<ThingModelMessage> 接口，
 *   handler() 方法作为消息消费的入口，由消息队列框架回调触发。
 *
 *   消息流：
 *   MQ Topic (device_thing / {type}_device_thing)
 *     → MqConsumer 拉取消息
 *       → ConsumerHandler.handler() 回调
 *         → 路由匹配 → onMessage()
 *
 * ============================================================================
 * 【水平扩展说明】
 *   假设有 3 个 MQTT 组件实例（MQTT-1、MQTT-2、MQTT-3）：
 *   - 设备 A 连接到 MQTT-1 → Redis 路由：str:iot:device:router:pk_A:dn_A = mqtt_MQTT-1-id
 *   - 设备 B 连接到 MQTT-2 → Redis 路由：str:iot:device:router:pk_B:dn_B = mqtt_MQTT-2-id
 *   - 当设备 A 上报数据时，三个实例都收到消息：
 *     MQTT-1：路由匹配 → 处理 ✓
 *     MQTT-2：路由不匹配 → 忽略
 *     MQTT-3：路由不匹配 → 忽略
 *
 * @see ThingComponent 物模型组件（继承此类，提供物模型消息分发）
 *
 * abstract 修饰符 用来 告诉java 这个类不完整不能直接new 智能被继承，用来定义通用功能
 * final 修饰符 告诉类这个是最终的 不可以修改的
 */
public abstract class AbstractComponent implements Component, ConsumerHandler<ThingModelMessage> {

    /**
     * 组件发现锁
     * 确保组件发现和注册过程的线程安全
     */
    private final Object discoverLock = new Object();

    /**
     * 聚合的外部依赖服务
     * 包含 MQ 消费者/生产者、Redis 模板、ComponentApi、DeviceApi
     */
    protected final ComponentServices componentServices;

    /**
     * 组件实例唯一 ID（UUID）
     * 用于组件发现时标识自身，以及 Redis 路由中的组件标识
     */
    private final String componentId;

    /**
     * 是否已订阅组件发现回复消息
     * 防止重复订阅
     */
    private Boolean isSubDiscoverReply = false;

    /**
     * 【构造函数】- 初始化组件发现机制和消息订阅
     *
     * 执行流程：
     *  1. 生成组件唯一 ID（UUID）
     *  2. 订阅 COMPONENT_DISCOVER_REPLY_TOPIC，等待组件管理回复
     *     - 收到回复后，检查 componentId 是否匹配（确保是本组件的回复）
     *     - 匹配成功后，订阅本组件的设备消息 topic（{type}_device_thing）
     *  3. 立即发送一次组件发现消息
     *  4. 启动独立线程持续发送发现消息（配合定时任务）
     *
     * 【为什么需要组件发现？】
     *  组件是动态的，可能有多个同类型组件实例在运行（水平扩展）。
     *  组件管理服务需要知道有哪些组件在运行，以便管理配置和路由消息。
     *
     * @param componentServices 依赖服务聚合对象
     */
    protected AbstractComponent(ComponentServices componentServices) {
        this.componentServices = componentServices;
        this.componentId = IdUtil.fastSimpleUUID();

        // 【关键】订阅组件发现回复消息
        // 当组件管理服务收到 COMPONENT_DISCOVER_TOPIC 消息后，会回复 COMPONENT_DISCOVER_REPLY_TOPIC
        // 这里监听回复，确认组件注册成功后订阅本组件的设备消息
        componentServices.getComponentMessageConsumer().consume(COMPONENT_DISCOVER_REPLY_TOPIC,
                (msg) -> {
                    // 检查消息类型：必须是 COMPONENT_DISCOVER_REPLY_TOPIC
                    if (Objects.equals(msg.getContent(), COMPONENT_DISCOVER_REPLY_TOPIC)) {
                        synchronized (discoverLock) {
                            // 【关键】检查 componentId 是否匹配，确保回复是给本组件的
                            // 因为可能有多个组件实例同时发送发现消息
                            if (!Objects.equals(msg.getComponentId(), getId())) {
                                return;
                            }
                            // 组件成功注册，开始订阅本组件的设备消息
                            // 订阅的 topic 格式：{componentType}_device_thing（如 mqtt_device_thing）
                            // 消费处理逻辑：handler() 方法
                            if(!isSubDiscoverReply){
                                componentServices.getConsumer().consume(Constants.getSendToDeviceTopic(getRouter()), this);
                                isSubDiscoverReply = true;
                            }

                        }
                        return;
                    }
                });

        // 立即发送一次组件发现消息（不等定时任务）
        publishDiscover();
        // 通过独立线程再发一次（确保消息送达）
        Executors.newSingleThreadExecutor().submit(this::publishDiscover);
    }

    /**
     * 【定时发布组件发现消息】
     * 每 3 秒执行一次，持续向平台宣告自己的存在。
     *
     * 组件管理服务监听 COMPONENT_DISCOVER_TOPIC，收到后发现新组件或更新组件信息。
     * 这种"心跳发现"模式确保：
     *  - 组件启动后能被及时发现
     *  - 组件异常退出后能被感知（心跳停止）
     *  - 支持组件的动态扩缩容
     *
     * 发送的消息包含：
     *  - id：消息唯一 ID
     *  - type：组件类型（如 "mqtt"）
     *  - name：组件名称
     *  - componentId：组件实例 ID
     */
    @SneakyThrows
    @Scheduled(fixedRate = 3000)
    private void publishDiscover() {
        componentServices.getComponentMessageProducer().publish(COMPONENT_DISCOVER_TOPIC, ComponentMessage.builder()
                .id(IdUtil.fastSimpleUUID())
                .time(System.currentTimeMillis())
                .content(COMPONENT_DISCOVER_TOPIC)
                .type(getType())
                .name(getName())
                .componentId(getId())
                .build());
    }

    @Override
    public String getId() {
        return this.componentId;
    }


    /**
     * 【下行消息消费入口】- 组件接收平台下发指令的唯一入口
     *
     * <p>本方法是消息队列回调的消费端，被 MQ 框架触发调用。
     * ComponentManager.sendToDevice() 发布消息到 {componentType}_device_thing topic，
     * 组件的 MQ Consumer 收到后回调此方法。</p>
     *
     * <h3>完整下行链路</h3>
     * <pre>
     * 前端/API 控制请求
     *   → DeviceCtrlServiceImpl.send()
     *     → ComponentManager.sendToDevice()
     *       → Redis 查路由: str:iot:device:router:{pk}:{dn}
     *         → MQ publish: {componentType}_device_thing（如 ge100d_device_thing）
     *           → 本方法 handler() ← 下行入口
     *             → Redis 二次路由确认
     *               → onMessage() → 类型分发 → 协议下发 → 硬件
     * </pre>
     *
     * <h3>路由判断</h3>
     * <ol>
     *   <li>从 Redis 查询设备归属组件: str:iot:device:router:{pk}:{dn}</li>
     *   <li>Value 格式: {componentType}_{componentId}，如 "ge100d_a1b2c3d4"</li>
     *   <li>与本组件标识对比: getRouter() = "{type}_{componentId}"</li>
     *   <li>匹配 → 本组件处理，进入类型分发；不匹配 → 忽略（水平扩展场景）</li>
     * </ol>
     *
     * <h3>类型分发</h3>
     * onMessage() 根据 msg.type 映射到具体协议方法:
     * <ul>
     *   <li>"service"  → serviceInvoke()   ← 下行服务调用</li>
     *   <li>"property" → propertySet() / propertyGet()</li>
     *   <li>"config"   → deviceConfig()    ← 配置下发</li>
     *   <li>"ota"      → deviceOta()       ← 固件升级</li>
     * </ul>
     *
     * @param msg ThingModelMessage（由 ComponentManager.sendToDevice() 构造并发布）
     */
    public void handler(ThingModelMessage msg) {
        String routerKey = getRouterKey(msg);
        String router = componentServices.getStringRedisTemplate().opsForValue().get(routerKey);
        if (!getRouter().equals(router)) {
            return;
        }
        onMessage(msg);
    }

    /**
     * 【发送消息】- 将 ThingModelMessage 发布到消息队列，同时更新 Redis 设备路由
     *
     * 此方法用于设备上报数据后的消息发送流程。
     *
     * 执行步骤：
     *  1. 在 Redis 中建立/更新设备→组件的路由映射
     *     Key: str:iot:device:router:{pk}:{dn}
     *     Value: {componentType}_{componentId}
     *     （这样后续下发指令时可以快速找到设备所属组件）
     *  2. 将消息发布到 device_thing topic
     *     （规则引擎和其他组件会消费此消息）
     *
     * @param message ThingModelMessage 要发送的物模型消息
     */
    @Override
    public void sendMessage(ThingModelMessage message) {
        String key = getRouterKey(message);
        String router = getRouter();
        // 【关键】更新 Redis 设备路由缓存
        // 记录"设备 X 由组件 Y 管理"的映射关系
        componentServices.getStringRedisTemplate().opsForValue().set(key, router);
        //发布消息到 device_thing topic，供规则引擎消费
        componentServices.getProducer().publish(Constants.THING_MODEL_MESSAGE_TOPIC, message);
    }

    /**
     * 【获取设备路由 Key】
     * 格式：str:iot:device:router:{productKey}:{deviceName}
     *
     * @param message ThingModelMessage
     * @return Redis Key 字符串
     */
    private String getRouterKey(ThingModelMessage message) {
        return Constants.getRedisDeviceRouter(message.getProductKey(), message.getDn());
    }

    /**
     * 【获取本组件的路由标识】
     * 格式：{componentType}_{componentId}
     * 例如：mqtt_a1b2c3d4e5f6
     *
     * 这个值作为 Redis 路由的 Value，表示"设备由哪个组件管理"
     */
    protected String getRouter() {
        return String.format("%s_%s", getType(), getId());
    }



    /**
     * 【缓存设备对应的组件信息】
     *
     * 在 Redis 中建立设备→组件的映射关系。
     * Key: str:iot:device:router:{pk}:{dn}
     * Value: {componentType}_{componentId}
     *
     * 使用场景：
     *  - 设备认证通过后，缓存设备与组件的绑定关系
     *  - 设备注册成功后，确保后续消息能正确路由
     *
     * @param pk 产品 Key（ProductKey）
     * @param dn 设备名称（DeviceName）
     */
    protected void cacheDeviceComponentInfo(String pk, String dn) {
        String key = Constants.getRedisDeviceRouter(pk, dn);
        String router = getRouter();
        componentServices.getStringRedisTemplate().opsForValue().set(key, router);
    }

}
