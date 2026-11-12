package com.enjoyiot.eiot.component.modbusCustom.parser;

import lombok.Data;

/**
 * ============================================================================
 * 【设备注册包】- 网关首次连接时发送的设备身份信息
 *
 * 【包格式】
 *  原始数据：r_{productKey}_{dn}_{slaveId}
 *  示例：    r_prod_001_A001_1
 *
 * 【字段说明】
 *  - productKey：产品标识，对应平台中的产品定义（物模型所属）
 *  - dn：设备序列号（Device Name），设备在平台中的唯一标识
 *  - slaveId：Modbus 从机地址（1-247），用于 Modbus 协议层的设备寻址
 *
 * ============================================================================
 * 【解析逻辑】（DataDecoder.decode()）
 *
 *  1. 判断：asciiStr.startsWith("r_") → 是注册包
 *  2. 分割：asciiStr.split("_") → ["r", "prod_001", "A001", "1"]
 *  3. 校验：split.length == 4（必须恰好 4 段）
 *  4. 提取：productKey=split[1], dn=split[2], slaveId=split[3]
 *
 * ============================================================================
 * 【处理逻辑】（ModbusComponent.handle()）
 *
 *  1. 提取 dn、productKey、slaveId
 *  2. 更新 client 信息（dn、pk、slaveId）
 *  3. 调用 DeviceApi.registerDevice() 在平台注册设备
 *     - 如果设备不存在 → 新建（自动绑定产品）
 *     - 如果设备已存在 → 返回已有信息
 *  4. 加载物模型：modbusThingModelApi.findByProductKey(productKey)
 *     → 缓存到 pkToThingModel
 *  5. 将 client 存入 clientMap（dn → client）
 *     → 后续 readTask 可以通过 dn 找到对应连接
 *
 * @see DataDecoder 解码逻辑
 * @see com.enjoyiot.eiot.component.modbusCustom.service.ModbusComponent 处理逻辑
 */
@Data
public class RegisterDataPackage extends DataPackage {

    /**
     * 设备序列号（Device Name）
     * 示例：A001
     */
    private String dn;

    /**
     * 产品 Key（Product Key）
     * 示例：prod_001
     * 用于查找产品物模型定义（属性列表、寄存器地址等）
     */
    private String productKey;

    /**
     * 【Modbus 从机地址（Slave ID）】
     *
     * 取值范围：1-247
     *
     * 在 Modbus 协议中：
     *  - 0：广播地址（所有从机都接收，但不应答）
     *  - 1-247：从机个体地址
     *  - 248-255：保留
     *
     * 此字段会被写入 MBAP Header 的 Unit ID 字段。
     * 当网关下挂多个 Modbus 设备时，通过不同的 slaveId 区分。
     */
    private Byte slaveId;
}
