
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

/**
 * 【设备组件接口】- IoT 组件框架的顶层契约
 *
 * ============================================================================
 * 【设计目标：插件化协议适配层】
 * Enjoy-IoT 的组件系统是一套"插件化"架构，允许平台同时支持多种通讯协议。
 * 每种协议对应一个组件实现，通过统一的接口契约接入平台。
 *
 * 目前已实现的协议组件：
 *  ┌──────────────────────┬──────────────┬──────────────────────────────────┐
 *  │ 组件类                │ getType()    │ 说明                              │
 *  ├──────────────────────┼──────────────┼──────────────────────────────────┤
 *  │ EmqxComponent        │ "emqx"       │ 通过外部 EMQX Broker 中转的 MQTT  │
 *  │ MqttComponent        │ "mqtt"       │ 内置 Vert.x MQTT Server，直连模式  │
 *  │ TcpComponent         │ "tcp"        │ 自定义 TCP 协议（私有二进制协议）   │
 *  │ CoapComponent        │ "coap"       │ CoAP 轻量级协议（UDP 之上）        │
 *  │ HttpComponent        │ "http"       │ HTTP/REST 协议适配                │
 *  │ UdpComponent         │ "udp"        │ UDP 协议适配（UWB 等）             │
 *  │ ModbusComponent      │ "modbus-custom"│ Modbus-TCP 协议（工业采集）      │
 *  └──────────────────────┴──────────────┴──────────────────────────────────┘
 *
 * ============================================================================
 * 【组件完整生命周期】
 *
 *  阶段1：实例化（Spring Boot 启动时）
 *    Spring @Component 扫描 → 创建组件 Bean → 调用构造函数
 *    → 生成 componentId（UUID）
 *    → 订阅 COMPONENT_DISCOVER_REPLY_TOPIC（等待注册回复）
 *    → 立即发送 COMPONENT_DISCOVER_TOPIC（宣告自身存在）
 *
 *  阶段2：发现注册（运行时持续）
 *    AbstractComponent.publishDiscover() 每 3 秒发送心跳
 *    → ComponentManager.componentDiscover() 收到消息
 *    → 组件类型首次出现 → 入库（eiot_component 表，status=0 停用）
 *    → 回复 COMPONENT_DISCOVER_REPLY_TOPIC
 *    → 组件收到回复后，订阅 {type}_device_thing topic
 *
 *  阶段3：配置获取（运行时每 5 秒轮询）
 *    ThingComponent.config() 调用 ComponentApi.getInfo(type)
 *    → 从 eiot_component 表读取 status + config JSON
 *    → 检测变更：status 变化或 config 变化
 *    → 调用 stateChange(enable, config)
 *    → enable=true：解析 config → 启动协议服务（如 MQTT Broker）
 *    → enable=false：停止协议服务
 *
 *  阶段4：消息路由（设备上线后）
 *    上行：设备上报 → 组件接收 → report() → 写入 Redis 路由 + 发布到 device_thing
 *    下行：平台下发 → ComponentManager.sendToDevice() → 查 Redis 路由
 *         → 发布到 {type}_device_thing → 组件 handler() 匹配路由
 *         → onMessage() 分发 → 协议方法（如 serviceInvoke）→ 发给设备
 *
 * ============================================================================
 * 【扩展新组件的步骤】
 *  1. 创建模块：如 iot-component-myprotocol
 *  2. 继承 ThingComponent：实现 getType()、getName()、stateChange()
 *  3. 实现 6 个下行抽象方法：serviceInvoke()、propertyGet()、propertySet() 等
 *  4. 添加 Spring @Component 注解
 *  5. 启动后自动发现 → 后台可见 → 配置后启用
 *
 * @see AbstractComponent 抽象实现（消息路由、组件发现）
 * @see ThingComponent 物模型组件（配置轮询、消息分发）
 */
public interface Component {

    /**
     * 获取组件实例唯一 ID
     * 每次实例化时自动生成（UUID），用于组件发现和消息路由
     */
    String getId();

    /**
     * 获取组件类型标识
     * 例如 "mqtt"、"http"、"coap" 等，用于组件发现时的类型匹配
     */
    String getType();

    /**
     * 获取组件名称
     * 用于显示和日志标识
     */
    String getName();

    /**
     * 【发送消息】- 将 ThingModelMessage 发布到消息队列
     *
     * 发送时会同步更新 Redis 设备路由缓存：
     *  Key: str:iot:device:router:{pk}:{dn}
     *  Value: {componentType}_{componentId}
     *
     * 这样后续下发指令时，可以通过 Redis 快速找到设备所属的组件实例
     */
    void sendMessage(ThingModelMessage message);

    /**
     * 【接收/处理消息】- 消费从消息队列收到的 ThingModelMessage
     *
     * 消息路由过程：
     *  1. handler() 方法收到消息后，先从 Redis 查询设备路由
     *  2. 如果路由指向本组件，则调用 onMessage() 处理
     *  3. ThingComponent.onMessage() 根据 message.type 分发到具体处理逻辑
     */
    void onMessage(ThingModelMessage message);
}