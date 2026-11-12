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
package com.enjoyiot.eiot.ruleengine.handler.sys;


import com.enjoyiot.eiot.common.constant.Constants;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.handler.DeviceMessageHandler;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.eiot.message.core.MqProducer;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 【设备配置处理器】- 处理设备配置获取请求，回复配置信息给设备
 *
 * 这是 Handler 链中的第 4 个处理器，负责处理设备发起的配置获取请求。
 * 当设备上报 identifier="get" 的 config 类型消息时，从平台查询设备配置并下发。
 *
 * 【处理流程】
 *  1. 识别配置获取请求（type="config", identifier="get"）
 *  2. 从 DeviceApi 查询设备配置信息
 *  3. 从 Redis 查询设备路由信息（找到设备所属的组件）
 *  4. 构造配置回复消息（identifier="get_reply", toClient=true）
 *  5. 发布到 {componentType}_device_thing topic，由组件下发给设备
 *
 * 【消息路由】
 *  配置回复走"下行总线"：通过 Redis 路由找到设备所属组件，
 *  消息发到该组件的专属 topic，组件收到后通过 MQTT/HTTP 下发给设备。
 *
 * 【执行顺序】第 4 位
 *  在状态检查之后、规则匹配之前执行，确保设备状态已更新后再处理配置。
 */
@Slf4j
@Service
public class DeviceConfigHandler implements DeviceMessageHandler {

    @Autowired
    private DeviceApi deviceApi;

    /** Redis 模板，用于查询设备路由信息 */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 消息生产者，用于发布配置回复消息 */
    @Autowired
    private MqProducer<ThingModelMessage> producer;

    @Override
    public void handle(ThingModelMessage msg) {
        String identifier = msg.getIdentifier();
        // 【过滤】仅处理配置获取请求（identifier = "get"）
        if (!ThingModelMessage.ID_CONFIG_GET.equals(identifier)) {
            return;
        }
        // 从平台查询设备的配置信息
        DeviceConfig deviceConfig = deviceApi.getDeviceConfig(msg.getDeviceId());
        if (deviceConfig == null) {
            return;
        }

        try {
            // 解析配置 JSON 为 Map
            Map<String, Object> config = JsonUtils.parseObject(deviceConfig.getConfig(), Map.class);

            // 【关键】从 Redis 查询设备路由信息，找到设备所属的组件
            // Key: str:iot:device:router:{pk}:{dn}
            // Value: {componentType}_{componentId}
            String routerKey = Constants.getRedisDeviceRouter(msg.getProductKey(), msg.getDn());
            String router = stringRedisTemplate.opsForValue().get(routerKey);
            if (router == null) {
                log.warn("device config reply skipped, router missing. key:{}", routerKey);
                return;
            }

            // 构造配置回复消息
            ThingModelMessage reply = ThingModelMessage.builder()
                    .id(msg.getId())
                    .mid(msg.getMid())
                    .deviceId(msg.getDeviceId())
                    .productKey(msg.getProductKey())
                    .dn(msg.getDn())
                    .type(ThingModelMessage.TYPE_CONFIG)
                    .identifier(ThingModelMessage.ID_CONFIG_GET + ThingModelMessage.SERVICE_REPLY_SUFFIX) // "get_reply"
                    .data(config)
                    .occurred(System.currentTimeMillis())
                    .time(System.currentTimeMillis())
                    .toClient(Boolean.TRUE) // 标记为下行消息
                    .build();
            // 发布到设备所属组件的专属 topic（下行总线）
            producer.publish(Constants.getSendToDeviceTopic(router), reply);
        } catch (Throwable e) {
            log.error("send config to device failed, deviceId:{}", msg.getDeviceId(), e);
        }
    }
}
