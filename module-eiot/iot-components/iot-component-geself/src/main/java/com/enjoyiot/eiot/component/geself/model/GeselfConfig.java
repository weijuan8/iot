package com.enjoyiot.eiot.component.geself.model;

import com.enjoyiot.eiot.component.geself.constant.GeselfConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GESELF 继电器控制组件配置
 *
 * <p>【配置示例】
 * <pre>
 * {
 *   "mqttHost": "127.0.0.1",
 *   "mqttPort": 1883,
 *   "mqttUsername": "geself_server",
 *   "mqttPassword": "xxx",
 *   "slaveId": "0f",
 *   "ack": 1,
 *   "crc": 1,
 *   "commandIntervalMs": 150,
 *   "ackTimeoutMs": 10000
 * }
 * </pre>
 *
 * <p>【Topic 设计】
 * <ul>
 *   <li>上行: geself/{productKey}/{deviceName}/{moduleId}/serial/up</li>
 *   <li>下行: geself/{productKey}/{deviceName}/{moduleId}/serial/down</li>
 *   <li>pk / dn / moduleId 均从 topic 中提取，无需在配置中指定</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeselfConfig {
    private String mqttHost = "127.0.0.1";
    private int mqttPort = 1883;
    private String mqttUsername;
    private String mqttPassword;

    /**
     * 继电器板 Modbus 从站地址（2 位十六进制字符串，如 0f）。
     * 同一网关下挂不同地址的继电器板时按产品/组件分别配置。
     */
    private String slaveId = GeselfConstants.DEFAULT_SLAVE_ID;

    /** ack：1=要求网关上传应答数据（需要接收 retmb 就必须为 1） */
    private int ack = GeselfConstants.ACK_ON;

    /** crc：1=mb 字段不含 CRC，由网关自动计算（本组件始终下发裸帧） */
    private int crc = GeselfConstants.CRC_BY_GATEWAY;

    /** 多路拆分下发时相邻帧的间隔（毫秒） */
    private long commandIntervalMs = GeselfConstants.COMMAND_INTERVAL_MS;

    /**
     * 继电器参数标识符的固定开头（物模型中以该开头命名的属性才视为继电器开关）。
     *
     * <p>默认 {@code geself}，标识符中间段数不限，末段 {@code _序号} 即继电器路号，
     * 即 geself_rela_01、geself_rela_02 …（01 → 第 1 路）。
     * 若某产品的继电器属性另起开头，在组件配置里改写即可，无需改代码。
     * 做开头校验是为了避免产品物模型中其它「以 _数字 结尾」的属性
     * （如阈值、档位）被误当成继电器通道下发控制指令。</p>
     */
    private String relayPrefix = GeselfConstants.PROPERTY_PREFIX;

    /** 等待设备 retmb 应答的超时时间（毫秒），超时视为失败 */
    private long ackTimeoutMs = GeselfConstants.ACK_TIMEOUT_MS;

    /**
     * 从站地址取值，空值或非法格式回退到默认值。
     */
    public String effectiveSlaveId() {
        if (slaveId == null || !GeselfConstants.SLAVE_ID_PATTERN.matcher(slaveId).matches()) {
            return GeselfConstants.DEFAULT_SLAVE_ID;
        }
        return slaveId.toLowerCase();
    }

    /**
     * 继电器属性前缀取值，空值回退到默认值。
     */
    public String effectiveRelayPrefix() {
        if (relayPrefix == null || relayPrefix.isEmpty()) {
            return GeselfConstants.PROPERTY_PREFIX;
        }
        return relayPrefix;
    }
}
