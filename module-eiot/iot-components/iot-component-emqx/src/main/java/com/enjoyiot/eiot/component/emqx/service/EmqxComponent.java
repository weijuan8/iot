
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
package com.enjoyiot.eiot.component.emqx.service;


import com.enjoyiot.eiot.component.core.ComponentServices;
import com.enjoyiot.eiot.component.core.ThingComponent;
import com.enjoyiot.eiot.component.core.model.down.*;

import com.enjoyiot.eiot.component.emqx.model.MqttConfig;

import com.enjoyiot.framework.common.util.json.JsonUtils;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;



/**
 * EMQX 组件 - IoT 组件框架中的协议适配层
 *
 * 【角色定位】
 * 本类继承自 ThingComponent（物组件抽象基类），是整个 IoT 组件框架中 EMQX 协议的适配层。
 * 它在框架中的地位是"中间人"：
 *
 *   平台后端（消息队列）  ←→  EmqxComponent（本类）  ←→  EmqxVerticle（MQTT）  ←→  EMQX Broker  ←→  设备
 *       ↑                         ↑                          ↑
 *   通过 report() 上报         组件注册、指令下发             MQTT 协议通信
 *
 * 【核心职责】
 * 1. 【组件注册】向 IoT 框架注册自身（getType()="emqx", getName()="内置官方eqmx协议组件"）
 * 2. 【指令下发】将平台后端的设备指令（属性设置、服务调用等）转换为 MQTT 消息发给设备
 * 3. 【数据上报】接收 EmqxVerticle 从设备收到的数据，通过 report() 上报到平台消息队列
 *
 * 【生命周期】
 * 1. Spring 容器创建 EmqxComponent 实例
 * 2. IoT 框架通过 ThingComponent.config() 定时轮询配置变更
 * 3. 配置变更时调用 stateChange(enable, config)：
 *    - enable=true：解析配置 → 启动 EmqxVerticle
 *    - enable=false：停止 EmqxVerticle
 *
 * 【注册流程总结（完整链路）】
 * 第1步：EmqxStarter.init()
 *   → 创建 Vertx 实例，部署 EmqxVerticle（此时不连接 MQTT）
 *
 * 第2步：IoT 框架检测到组件配置变更
 *   → 调用 EmqxComponent.stateChange(true, jsonConfig)
 *
 * 第3步：EmqxComponent.stateChange()
 *   → 解析 JSON 配置为 MqttConfig
 *   → 调用 emqxVerticle.startServer(mqttConfig)
 *
 * 第4步：EmqxVerticle.startServer()
 *   → 配置 AuthVerticle（端口、密码）
 *   → 创建 MQTT 客户端
 *   → 启动定时重连任务（每 3 秒）
 *
 * 第5步：EmqxVerticle.run()（定时任务）
 *   → 阶段1：部署 AuthVerticle（启动认证 HTTP 服务）
 *   → 阶段2：连接 EMQX Broker，订阅 Topic
 *
 * 第6步：设备连接 EMQX
 *   → EMQX 回调 /mqtt/auth → AuthVerticle 调用 deviceApi.auth() 认证
 *   → 认证通过 → 设备上线
 *
 * 第7步：设备上报数据
 *   → EMQX 转发 → EmqxVerticle.publishHandler 处理
 *   → 调用 emqxComponent.report() → 发送到平台消息队列
 *
 * @see com.enjoyiot.eiot.component.emqx.EmqxStarter 启动入口
 * @see EmqxVerticle MQTT 连接管理
 * @see AuthVerticle 设备认证服务
 */
@Slf4j
@Component
public class EmqxComponent extends ThingComponent {

    /**
     * MQTT Verticle 实例，提供 MQTT 客户端连接能力
     */
    private final EmqxVerticle mqttVerticle;

    /**
     * 构造函数
     *
     * 通过构造注入获取 EmqxVerticle 和 ComponentServices（由 ThingComponent 管理）。
     * ComponentServices 是 IoT 框架的基础服务聚合类，提供 DeviceApi、消息队列生产者/消费者等。
     *
     * @param mqttVerticle      MQTT Verticle 实例
     * @param componentServices IoT 组件基础服务（DeviceApi、消息队列等）
     */
    public EmqxComponent(
            EmqxVerticle mqttVerticle,
            ComponentServices componentServices
    ) {
        super(componentServices);
        this.mqttVerticle = mqttVerticle;
    }

    /**
     * 返回组件类型标识
     *
     * "emqx" 是 IoT 框架中唯一标识本组件的 key，
     * 框架通过此 key 查找和调度对应的组件实例。
     */
    @Override
    public String getType() {
        return "emqx";
    }

    /**
     * 返回组件名称（用于 UI 展示）
     */
    @Override
    public String getName() {
        return "内置官方eqmx协议组件";
    }

    /**
     * 组件状态变更回调
     *
     * 【调用时机】
     * 由 ThingComponent.config() 定时轮询触发，当组件启用状态或配置发生变化时调用。
     *
     * 【处理逻辑】
     * 1. 将自身引用设置给 EmqxVerticle（方便 EmqxVerticle 回调 report()）
     * 2. enable=false → 停止 MQTT 服务
     * 3. enable=true  → 解析 JSON 配置 → 重启 MQTT 服务
     *
     * @param enable true=启用组件, false=禁用组件
     * @param config JSON 格式的 MqttConfig 配置字符串
     *               {"host":"127.0.0.1","port":1883,"ssl":false,"topics":"/sys/+/+/s/#","authPort":1884}
     * @return true=操作成功, false=操作失败
     */
    @Override
    public boolean stateChange(boolean enable, String config) {
        // 设置双向引用：EmqxVerticle 可以通过 emqxComponent 回调 report()
        mqttVerticle.setEmqxComponent(this);

        // 停止组件
        if (!enable) {
            mqttVerticle.stopServer();
            return true;
        }

        // 启用组件：需要有效配置
        if (config == null) {
            return false;
        }

        // 解析 JSON 配置
        MqttConfig mqttConfig = JsonUtils.parseObject(config, MqttConfig.class);
        if (mqttConfig == null) {
            log.error("parse json mqtt config failed.");
            return false;
        }

        // 先停止旧连接（如果有），再启动新连接
        mqttVerticle.stopServer();
        mqttVerticle.startServer(mqttConfig);
        return true;
    }

    /**
     * 设备 OTA 升级（当前未实现）
     */
    @Override
    protected void deviceOta(DeviceOta action) {

    }

    /**
     * 服务调用（平台 → 设备）
     *
     * 将平台下发的服务调用指令转换为 MQTT 消息发送给设备。
     *
     * 【Topic 格式】
     *   /sys/{productKey}/{deviceName}/c/service/{serviceName}
     *
     * 【消息格式】
     *   {
     *     "id": "消息ID",
     *     "method": "thing.service.{serviceName}",
     *     "params": {服务参数}
     *   }
     *
     * @param action 服务调用动作，包含 productKey、deviceName、服务名和参数
     */
    @Override
    protected void serviceInvoke(ServiceInvoke action) {
        String topic = String.format("/sys/%s/%s/c/service/%s",
                action.getProductKey(), action.getDeviceName(), action.getName());
        publish(
                action.getProductKey(),
                action.getDeviceName(),
                topic,
                new JsonObject()
                        .put("id", action.getId())
                        .put("method", "thing.service." + action.getName())
                        .put("params", action.getParams())
                        .toString()
        );
    }

    /**
     * 属性获取（平台 → 设备）
     *
     * 请求设备上报指定属性的当前值。
     *
     * 【Topic】/sys/{pk}/{dn}/c/service/property/get
     * 【Method】thing.service.property.get
     *
     * @param action 属性获取动作，包含 productKey、deviceName 和属性 key 列表
     */
    @Override
    protected void propertyGet(PropertyGet action) {
        String topic = String.format("/sys/%s/%s/c/service/property/get",
                action.getProductKey(), action.getDeviceName());
        publish(
                action.getProductKey(),
                action.getDeviceName(),
                topic,
                new JsonObject()
                        .put("id", action.getId())
                        .put("method", "thing.service.property.get")
                        .put("params", action.getKeys())
                        .toString()
        );
    }

    /**
     * 属性设置（平台 → 设备）
     *
     * 向设备下发属性配置指令。
     *
     * 【Topic】/sys/{pk}/{dn}/c/service/property/set
     * 【Method】thing.service.property.set
     *
     * @param action 属性设置动作，包含 productKey、deviceName 和属性键值对
     */
    @Override
    protected void propertySet(PropertySet action) {
        String topic = String.format("/sys/%s/%s/c/service/property/set",
                action.getProductKey(), action.getDeviceName());
        publish(
                action.getProductKey(),
                action.getDeviceName(),
                topic,
                new JsonObject()
                        .put("id", action.getId())
                        .put("method", "thing.service.property.set")
                        .put("params", action.getParams())
                        .toString()
        );
    }

    /**
     * 设备配置下发（平台 → 设备）
     *
     * 向设备下发配置信息。
     *
     * 【Topic】/sys/{pk}/{dn}/c/config/set
     * 【Method】thing.config.set
     *
     * @param action 设备配置动作，包含 productKey、deviceName 和配置内容
     */
    @Override
    protected void deviceConfig(DeviceConfig action) {
        String topic = String.format("/sys/%s/%s/c/config/set",
                action.getProductKey(), action.getDeviceName());

        publish(
                action.getProductKey(),
                action.getDeviceName(),
                topic,
                new JsonObject()
                        .put("id", action.getId())
                        .put("method", "thing.config.set")
                        .put("params", action.getConfig())
                        .toString()
        );
    }

    /**
     * 设备拓扑变更（当前未实现）
     */
    @Override
    protected void deviceTopoChange(DeviceTopoChange action) {

    }

    /**
     * 【MQTT 发布】- 下行消息的物理发送入口（EMQX 组件版）
     *
     * 所有下行操作最终汇聚到此方法，通过 EMQX Broker 将消息转发给设备。
     *
     * 【Topic 约定】
     *  下行 Topic 使用 /c/ 路径段（cloud→device），与上行 /s/（sensor→cloud）区分：
     *  - 上行（设备→平台）：/sys/{pk}/{dn}/s/{method}
     *  - 下行（平台→设备）：/sys/{pk}/{dn}/c/{method}
     *
     * 【QoS 策略】
     *  使用 AT_MOST_ONCE（QoS 0，最多一次），不保证送达。
     *  这是因为通过 EMQX Broker 转发，平台无法直接感知设备是否收到消息。
     *  如果需要可靠投递，应使用 MqttComponent（直连模式，QoS 1）。
     *
     * 【与 MqttComponent.publish() 的区别】
     *  - EmqxComponent：通过外部 Broker 中转，QoS 0，不管理设备连接
     *  - MqttComponent：直接管理 MQTT 连接，QoS 1，自行维护 endpointMap
     *
     * @param pk    产品 Key
     * @param dn    设备名称
     * @param topic MQTT Topic
     * @param msg   JSON 格式的消息体
     */
    private void publish(String pk, String dn, String topic, String msg) {
        mqttVerticle.getClient().publish(topic, Buffer.buffer(msg), MqttQoS.AT_MOST_ONCE, false, false);
    }

}
