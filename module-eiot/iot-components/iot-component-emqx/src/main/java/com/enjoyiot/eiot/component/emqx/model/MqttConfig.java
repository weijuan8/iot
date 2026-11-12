
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
package com.enjoyiot.eiot.component.emqx.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * EMQX MQTT 配置类
 *
 * 【配置来源说明】
 * 本类有两种配置来源方式：
 *
 * 方式1 - Spring 配置文件（application.yaml）：
 *   通过 @ConfigurationProperties(prefix = "emqx") 绑定 application.yaml 中的 emqx.* 配置项。
 *   这是 Spring Boot 启动时自动加载的默认配置。
 *
 *   示例配置：
 *   emqx:
 *     host: 127.0.0.1
 *     port: 1883
 *     ssl: false
 *     topics: /sys/+/+/s/#
 *     auth-port: 1884
 *
 * 方式2 - 运行时动态配置（JSON）：
 *   在 EmqxComponent.stateChange() 方法中，平台会通过 JSON 字符串传入配置。
 *   例如：{"host":"192.168.1.100","port":1883,"ssl":false,"topics":"/sys/+/+/s/#","authPort":1884}
 *   这种方式支持运行时动态切换 EMQX Broker 连接，无需重启服务。
 *
 * 【配置项说明】
 * - host:      EMQX Broker 的 IP 地址或域名
 * - port:      EMQX Broker 的 MQTT 服务端口（默认 1883）
 * - ssl:       是否启用 SSL/TLS 加密连接
 * - topics:    需要订阅的 MQTT 主题，支持通配符 +（单层）和 #（多层）
 *              例如 "/sys/+/+/s/#" 表示订阅所有设备的上行消息
 * - authPort:  本地认证 HTTP 服务的监听端口（EMQX Broker 会回调此端口进行设备认证）
 *
 * @see com.enjoyiot.eiot.component.emqx.service.EmqxVerticle#startServer(MqttConfig)
 * @see com.enjoyiot.eiot.component.emqx.service.EmqxComponent#stateChange(boolean, String)
 */
@Data
@Builder
@Configuration
@ConfigurationProperties(prefix = "emqx")
@NoArgsConstructor
@AllArgsConstructor
public class MqttConfig {

    /**
     * EMQX Broker 主机地址
     * 例如：127.0.0.1 或 emqx.example.com
     */
    private String host;

    /**
     * EMQX Broker MQTT 服务端口
     * 默认通常为 1883（非SSL）或 8883（SSL）
     */
    private int port;

    /**
     * 是否启用 SSL/TLS 加密连接
     */
    private boolean ssl;

    /**
     * 需要订阅的 MQTT 主题列表，多个主题用逗号分隔
     * 支持通配符：+ 匹配单层，/sys/+/+/s/# 匹配所有产品下所有设备的上行消息
     * 示例："/sys/+/+/s/#,/sys/+/+/c/#"
     */
    private String topics;

    /**
     * 本地设备认证 HTTP 服务的端口
     * AuthVerticle 会在此端口启动 HTTP 服务，EMQX Broker 通过 HTTP 回调进行设备认证和 ACL 鉴权
     */
    private int authPort;
}
