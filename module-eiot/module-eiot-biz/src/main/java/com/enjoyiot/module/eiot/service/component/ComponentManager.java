
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
package com.enjoyiot.module.eiot.service.component;

import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.common.constant.Constants;
import com.enjoyiot.eiot.common.thing.ComponentMessage;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.message.core.ConsumerHandler;
import com.enjoyiot.eiot.message.core.MqConsumer;
import com.enjoyiot.eiot.message.core.MqProducer;
import com.enjoyiot.framework.common.exception.ServiceException;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentCreateReqVO;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;


import java.util.HashSet;
import java.util.Set;

import static com.enjoyiot.eiot.common.constant.Constants.*;
import static com.enjoyiot.eiot.common.enums.ErrorCodeConstants.ROUTER_NOT_EXISTS;

/**
 * 【组件管理器】- 下行消息路由的核心枢纽 + 组件自动发现注册
 *
 * ============================================================================
 * 【角色定位】
 * ComponentManager 是整个组件系统在"平台端"的核心管理模块。
 * 它扮演两个角色：
 *   1. 组件发现服务：监听并注册新启动的协议组件
 *   2. 下行消息路由器：将平台指令精准投递到设备所在组件
 *
 * ============================================================================
 * 【角色1：组件发现服务】
 *
 * 完整流程：
 *   ① 组件实例启动（AbstractComponent 构造函数）
 *     → 每 3 秒发送 COMPONENT_DISCOVER_TOPIC 消息
 *     → 消息内容：{type:"mqtt", name:"内置官方mqtt协议组件", componentId:"a1b2c3d4"}
 *
 *   ② ComponentManager.handler() 收到消息
 *     → 调用 componentDiscover(msg)
 *
 *   ③ componentDiscover() 处理：
 *     → 检查 components Set 是否已包含此 type
 *     → 首次发现 → 调用 componentService.createComponent() 入库
 *       → INSERT INTO eiot_component(type, name, status) VALUES('mqtt', '内置官方mqtt协议组件', 0)
 *       → status=0 表示"已发现但停用"，需要管理员手动启用
 *     → 回复 COMPONENT_DISCOVER_REPLY_TOPIC（携带 componentId）
 *
 *   ④ 组件收到回复 → 匹配 componentId → 订阅 {type}_device_thing topic
 *
 * ============================================================================
 * 【角色2：下行消息路由器】
 *
 * sendToDevice() 是下行消息的唯一出口，完整链路：
 *
 *   DeviceCtrlController（HTTP API）
 *     → DeviceCtrlServiceImpl.send()
 *       → ComponentManager.sendToDevice(msg)  ← 本方法
 *         → Redis GET str:iot:device:router:{pk}:{dn}
 *           → 查到：mqtt_a1b2c3d4
 *             → 发布到 topic: mqtt_device_thing
 *               → MqttComponent.handler() 消费
 *                 → 路由匹配确认
 *                   → onMessage() → serviceInvoke() → MQTT publish → 设备
 *
 *   sendToDevice() 路由流程详解：
 *   1. 从 ThingModelMessage 中提取 pk + dn
 *   2. 构建 Redis Key：str:iot:device:router:{pk}:{dn}
 *   3. 从 Redis 查询 Value：{componentType}_{componentId}
 *      （此映射关系由 AbstractComponent.cacheDeviceComponentInfo() 建立）
 *   4. 如果找不到路由 → 抛出 ROUTER_NOT_EXISTS 异常（设备未连接）
 *   5. 发布消息到 topic：{componentType}_device_thing
 *      （只有对应组件类型才会消费此 topic）
 *
 * ============================================================================
 * 【Redis 路由 Key 说明】
 *  Key 格式：str:iot:device:router:{productKey}:{deviceName}
 *  Value 格式：{componentType}_{componentId}
 *  例如：str:iot:device:router:prod_001:dev_001 → mqtt_a1b2c3d4
 *
 * 【Topic 约定】
 *  COMPONENT_DISCOVER_TOPIC      = "component_discover"        (组件宣告存在)
 *  COMPONENT_DISCOVER_REPLY_TOPIC = "component_discover_reply" (平台回复注册)
 *  device_thing                  = "device_thing"              (设备消息总 topic)
 *  {type}_device_thing           = "mqtt_device_thing"         (组件专属 topic)
 *
 * ============================================================================
 * 【为什么自动发现的组件默认 status=0（停用）？】
 *  安全考虑：新组件需要管理员审核配置后才能启用。
 *  管理员在后台配置组件参数（如 Broker 地址、端口等），
 *  然后将 status 改为 1，组件通过 config() 轮询感知到变更后自动启动。
 *
 * @see AbstractComponent 组件抽象基类（路由缓存的建立者）
 * @see Constants#getSendToDeviceTopic 组件 topic 生成
 */
@Service
public class ComponentManager implements ConsumerHandler<ComponentMessage> {

    private final MqProducer<ThingModelMessage> producer;
    private final MqProducer<ComponentMessage> componentMessageMqProducer;

    private final StringRedisTemplate stringRedisTemplate;

    private final ComponentService componentService;

    private Set<String> components = new HashSet<>();

    public ComponentManager(MqConsumer<ThingModelMessage> consumer,
                            MqProducer<ThingModelMessage> producer,
                            MqConsumer<ComponentMessage> componentMessageMqConsumer,
                            MqProducer<ComponentMessage> componentMessageMqProducer,
                            StringRedisTemplate stringRedisTemplate,
                            ComponentService componentService) {
        this.producer = producer;
        this.componentMessageMqProducer = componentMessageMqProducer;
        this.stringRedisTemplate = stringRedisTemplate;
        this.componentService = componentService;

        //订阅组件发现
        componentMessageMqConsumer.consume(COMPONENT_DISCOVER_TOPIC, this);
    }

    @Override
    public void handler(ComponentMessage msg) {
        //组件发现消息
        componentDiscover(msg);
    }

    /**
     * 【通过路由发送消息给设备】- 下行消息的核心路由方法
     *
     * 这是整个下行链路中最关键的路由环节。方法通过 Redis 查找设备当前连接的组件实例，
     * 将消息精准投递到对应组件的专属 topic。
     *
     * 【详细流程】
     *  1. 构建 Redis Key：str:iot:device:router:{pk}:{dn}
     *     （例如 str:iot:device:router:prod_001:dev_001）
     *
     *  2. 从 Redis 查询路由值
     *     （例如 "mqtt_a1b2c3d4"，表示设备连接在 componentId=a1b2c3d4 的 MQTT 组件上）
     *
     *  3. 如果路由为空 → 抛出 ROUTER_NOT_EXISTS 异常
     *     （说明设备未上线或未建立路由映射）
     *
     *  4. 发布消息到组件专属 topic
     *     topic 格式：{componentType}_device_thing（如 "mqtt_device_thing"）
     *     Constants.getSendToDeviceTopic(router) 从路由值中提取 componentType
     *
     * 【路由映射建立时机】
     *  - AbstractComponent.cacheDeviceComponentInfo()：设备认证通过后建立映射
     *  - AbstractComponent.sendMessage()：设备上报数据时更新映射
     *
     * 【为什么不用 pk/dn 直接发送？】
     *  一个组件类型可能有多个实例在运行（水平扩展），必须精确定位到
     *  设备实际连接的那个实例，否则消息会丢失或发错。
     *
     * @param msg ThingModelMessage（包含 pk、dn、type、identifier、data 等）
     * @throws ServiceException 当设备路由不存在时（设备离线）
     */
    /**
     * 【下行消息核心路由方法】
     *
     * <p>通过 Redis 查找设备当前连接的组件实例，将消息精准投递到对应组件的专属 topic。</p>
     *
     * <h3>路由流程</h3>
     * <ol>
     *   <li>构建 Redis Key: str:iot:device:router:{productKey}:{deviceName}
     *       例: str:iot:device:router:apFx3ja3p3nHiSRW:ge1</li>
     *   <li>从 Redis 查路由 Value: {componentType}_{componentId}
     *       例: "ge100d_a1b2c3d4"（设备在 componentId=a1b2c3d4 的 GE100D 组件上）</li>
     *   <li>路由为空 → 设备离线或未建立连接 → 抛 ROUTER_NOT_EXISTS 异常</li>
     *   <li>提取 componentType 前缀 → 构建 topic: {componentType}_device_thing
     *       例: "ge100d_device_thing"</li>
     *   <li>发布到组件专属 topic，只有对应组件会消费此消息</li>
     * </ol>
     *
     * <h3>路由映射建立时机</h3>
     * <ul>
     *   <li>AbstractComponent.sendMessage(): 设备上报数据时写入/更新路由</li>
     *   <li>AbstractComponent.cacheDeviceComponentInfo(): 设备认证时建立路由</li>
     * </ul>
     *
     * @param msg ThingModelMessage（必须包含 pk + dn，用于路由查找）
     * @throws ServiceException 设备路由不存在时（设备离线或未注册）
     */
    public void sendToDevice(ThingModelMessage msg) {
        // 构建 Redis Key: str:iot:device:router:{productKey}:{deviceName}
        String routerKey = getRouterKey(msg);
        // 从 Redis 查询该设备的"归属组件"路由信息
        // Value 格式: {componentType}_{componentId}，如 "ge100d_a1b2c3d4"
        String router = stringRedisTemplate.opsForValue().get(routerKey);
        // 路由不存在 → 设备离线或从未连接 → 无法下发
        if (StringUtils.isBlank(router)) {
            throw new ServiceException(ROUTER_NOT_EXISTS);
        }
        // 提取 componentType 构建 topic: {type}_device_thing（如 ge100d_device_thing）
        // 只有该组件类型订阅了此 topic，实现精确投递到设备所在组件
        producer.publish(Constants.getSendToDeviceTopic(router), msg);
    }

    /**
     * 【组件发现处理】- 接收组件的注册消息并回复
     *
     * 组件启动后会定期发送 COMPONENT_DISCOVER_TOPIC 消息宣告自己的存在。
     * 此方法处理这些发现消息：
     *
     *  1. 提取组件信息：type（如 "mqtt"）、componentId（实例 UUID）、name
     *  2. 通过 components Set 去重，避免重复入库
     *  3. 新组件类型 → 入库（componentService.createComponent）
     *  4. 回复 COMPONENT_DISCOVER_REPLY_TOPIC，携带 componentId
     *     （组件收到回复后，验证 componentId 匹配，然后订阅设备消息 topic）
     *
     * @param msg 组件发现消息
     */
    private void componentDiscover(ComponentMessage msg) {
        //组件类型（如 "mqtt"、"emqx"、"tcp" 等）
        String type = msg.getType();
        //组件实例唯一 ID（UUID）
        String componentId = msg.getComponentId();
        //组件名称（如 "内置官方mqtt协议组件"）
        String name = msg.getName();

        // 新组件类型 → 入库
        if (!components.contains(type)) {
            if (componentService.getComponent(type) == null) {
                //组件信息入库
                componentService.createComponent(ComponentCreateReqVO.builder()
                        .type(type)
                        .name(name)
                        .status(0)
                        .build());
            }
            components.add(type);
        }

        //回复发现消息，componentId 让组件确认回复是给自己的
        componentMessageMqProducer.publish(COMPONENT_DISCOVER_REPLY_TOPIC, ComponentMessage.builder()
                .id(IdUtil.fastSimpleUUID())
                .content(COMPONENT_DISCOVER_REPLY_TOPIC)
                .componentId(componentId)
                .type(type)
                .time(System.currentTimeMillis())
                .name(name)
                .build());
    }

    /**
     * 【获取 Redis 路由 Key】
     * 格式：str:iot:device:router:{productKey}:{deviceName}
     *
     * @param message ThingModelMessage
     * @return Redis Key 字符串
     */
    private String getRouterKey(ThingModelMessage message) {
        return Constants.getRedisDeviceRouter(message.getProductKey(), message.getDn());
    }
}
