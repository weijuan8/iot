package com.enjoyiot.eiot.component.geio.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GEIO 多路继电器控制组件配置
 *
 * <p>【配置示例】
 * <pre>
 * {
 *   "mqttHost": "192.168.1.100",
 *   "mqttPort": 1883,
 *   "mqttUsername": "geio_server",
 *   "mqttPassword": "xxx",
 *   "heartbeatTimeout": 120
 * }
 * </pre>
 *
 * <p>【Topic 设计】
 * <ul>
 *   <li>上行: geio/{productKey}/{gatewayDn}/serial/up</li>
 *   <li>下行: geio/{productKey}/{gatewayDn}/serial/down</li>
 *   <li>pk 和 dn 都从 topic 中提取，无需在配置中指定</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeioConfig {
    private String mqttHost = "127.0.0.1";
    private int mqttPort = 1883;
    private String mqttUsername;
    private String mqttPassword;
    private int heartbeatTimeout = 120;
}
