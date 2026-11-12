
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

import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.common.thing.ComponentMessage;
import com.enjoyiot.eiot.message.core.MqConsumer;
import com.enjoyiot.eiot.message.core.MqProducer;
import com.enjoyiot.module.eiot.api.component.ComponentApi;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.module.YModuleApi;
import com.enjoyiot.module.eiot.api.product.ProductApi;
import com.enjoyiot.module.eiot.api.thingmodel.ThingModelApi;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 【组件服务聚合器】- 聚合组件所需的所有外部依赖
 *
 * 这是一个聚合模式（Facade Pattern）的应用，将所有外部依赖集中管理。
 * 组件通过 componentServices 访问所有基础设施，避免在每个组件中分散注入。
 *
 * 【聚合的服务】
 *  ┌─────────────────────────────────────────────────────────┐
 *  │ ComponentServices                                       │
 *  ├─────────────────────────────────────────────────────────┤
 *  │ consumer               → 设备消息消费者（消费 device_thing）     │
 *  │ producer               → 设备消息生产者（发布到 device_thing）     │
 *  │ componentMessageConsumer → 组件间消息消费者（消费组件发现回复）     │
 *  │ componentMessageProducer → 组件间消息生产者（发布组件发现）        │
 *  │ stringRedisTemplate    → Redis 操作（设备路由缓存）              │
 *  │ componentApi           → 组件配置 API（获取组件配置信息）         │
 *  │ deviceApi              → 设备 API（查询设备信息、注册设备等）      │
 *  │ thingModelApi          → 物模型 API（查询产品物模型属性）           │
 *  │ productApi             → 产品 API（查询产品列表、产品信息）          │
 *  └─────────────────────────────────────────────────────────┘
 *
 * 【消息流说明】
 *  设备消息（device_thing topic）：
 *    - 生产者：AbstractComponent.sendMessage() → producer
 *    - 消费者：AbstractComponent 实现 ConsumerHandler → consumer
 *
 *  组件消息（COMPONENT_DISCOVER_TOPIC）：
 *    - 生产者：AbstractComponent.publishDiscover() → componentMessageProducer
 *    - 消费者：AbstractComponent 构造中的 lambda → componentMessageConsumer
 */
@Data
@Component
public class ComponentServices {

    /**
     * 设备物模型消息消费者（MqConsumer<ThingModelMessage>）
     * 用于消费 device_thing topic 上的设备消息
     */
    @Resource
    private MqConsumer<ThingModelMessage> consumer;

    /**
     * 设备物模型消息生产者（MqProducer<ThingModelMessage>）
     * 用于将设备消息发布到 device_thing topic
     */
    @Resource
    private MqProducer<ThingModelMessage> producer;

    /**
     * 组件间消息生产者（MqProducer<ComponentMessage>）
     * 用于发布组件发现消息（COMPONENT_DISCOVER_TOPIC）
     */
    @Resource
    private MqProducer<ComponentMessage> componentMessageProducer;

    /**
     * 组件间消息消费者（MqConsumer<ComponentMessage>）
     * 用于消费组件发现回复消息（COMPONENT_DISCOVER_REPLY_TOPIC）
     */
    @Resource
    private MqConsumer<ComponentMessage> componentMessageConsumer;



    /**
     * Redis 字符串模板
     * 主要用于设备路由缓存：
     *  Key: str:iot:device:router:{pk}:{dn}
     *  Value: {componentType}_{componentId}
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 组件配置 API
     * 用于从平台获取组件的配置信息和运行状态
     */
    @Resource
    private ComponentApi componentApi;

    /**
     * 设备 API
     * 用于查询设备信息、设备注册、认证等操作
     */
    @Resource
    private DeviceApi deviceApi;

    /**
     * 物模型 API
     * 用于查询产品物模型，例如检查产品是否包含特定标识符的属性
     */
    @Resource
    private ThingModelApi thingModelApi;

    /**
     * 产品 API
     * 用于获取产品列表、查询产品信息
     */
    @Resource
    private ProductApi productApi;

    /**
     * 模块 API（供 GEIO 等组件自动注册设备时使用，跳过租户/超管权限校验）
     */
    @Resource
    private YModuleApi moduleApi;

}
