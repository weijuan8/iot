package com.enjoyiot.eiot.component.ge100d.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GE100D 网关透传组件配置
 *
 * <p>【配置示例】
 * <pre>
 * {
 *   "mqttHost": "192.168.1.100",
 *   "mqttPort": 1883,
 *   "mqttUsername": "ge100d_server",
 *   "mqttPassword": "xxx",
 *   "heartbeatTimeout": 120
 * }
 * </pre>
 *
 * <p>【Topic 设计】
 * <ul>
 *   <li>上行: ge100d/{productKey}/{gatewayDn}/serial/up</li>
 *   <li>下行: ge100d/{productKey}/{gatewayDn}/serial/down</li>
 *   <li>pk 和 dn 都从 topic 中提取，无需在配置中指定</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ge100dConfig {
    private String mqttHost = "127.0.0.1";
    private int mqttPort = 1883;
    private String mqttUsername;
    private String mqttPassword;
    private int heartbeatTimeout = 120;

    /**
     * 轮询状态查询命令固定为 "zqnetget0qz"，通过 MQTT 透传给 GE100D 硬件。
     * 硬件收到后回复 "zqnetret1qz" + 继电器状态数据。
     *
     * <p>组件会扫描所有产品的物模型，找出 identifier 以 "ge_" 开头的产品，
     * 定时向这些产品下所有设备发送轮询命令获取开关状态。</p>
     */
}
