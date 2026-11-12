package com.enjoyiot.eiot.component.modbusCustom.parser;

import com.digitalpetri.modbus.ModbusPdu;
import com.digitalpetri.modbus.codec.MbapHeader;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 【Modbus 响应包】- 从网关收到的 Modbus TCP 响应帧
 *
 * 【包结构】
 *
 *  Modbus TCP 响应帧格式（>7 字节）：
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │ MBAP Header (7 bytes)                                       │
 *  │  [TransactionID][ProtocolID][Length][UnitID]                 │
 *  │   2 bytes       2 bytes    2 bytes 1 byte                   │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │ Modbus PDU (变长)                                           │
 *  │  [FunctionCode][ByteCount][RegisterValues...]                │
 *  │   1 byte       1 byte     N*2 bytes                         │
 *  └─────────────────────────────────────────────────────────────┘
 *
 *  响应帧示例（读保持寄存器返回 2 个寄存器值）：
 *    Transaction ID: 0x0001   (与请求帧一致)
 *    Protocol ID:    0x0000
 *    Length:         0x0005   (后续 5 字节)
 *    Unit ID:        0x01
 *    Function Code:  0x03     (读保持寄存器)
 *    Byte Count:     0x04     (4 字节数据)
 *    Register 0:     0x0042   (66)
 *    Register 1:     0x00A0   (160)
 *
 * ============================================================================
 * 【解析逻辑】（DataDecoder.decode()）
 *
 *  1. 判断：buffer.length() > 7（非注册包、非心跳包）
 *  2. 解析 MBAP Header（前 7 字节）：
 *     MbapHeader.decode(buffer.getBuffer(0, 7).getByteBuf())
 *       → 提取 Transaction ID、Protocol ID、Length、Unit ID
 *  3. 解析 PDU（第 8 字节开始）：
 *     decoder.decode(buffer.getBuffer(7, buffer.length()).getByteBuf())
 *       → 根据 Function Code 反序列化为具体响应类型：
 *         - ReadCoilsResponse（线圈状态）
 *         - ReadDiscreteInputsResponse（离散输入状态）
 *         - ReadHoldingRegistersResponse（保持寄存器值）
 *         - ReadInputRegistersResponse（输入寄存器值）
 *
 * ============================================================================
 * 【处理逻辑】（ModbusComponent.handle()）
 *
 *  1. 从 MBAP Header 提取 Transaction ID
 *  2. 从 client.transactionMap 中取出对应的 Property
 *     - 如果找不到 → 忽略（可能是过期的响应）
 *  3. 根据 PDU 的 Function Code switch 分发：
 *     - ReadCoils → 提取 coilStatus 字节数组
 *     - ReadDiscreteInputs → 提取 inputStatus 字节数组
 *     - ReadHoldingRegisters → 提取 registers 字节数组
 *     - ReadInputRegisters → 提取 registers 字节数组
 *  4. 调用 property.parse(byte[]) 将原始字节转为业务值
 *     - property.parse() 内部按 dataType 和 sort（字节序）解析
 *  5. 调用 report(PropertyReport) 上报到平台
 *
 * @see DataDecoder 解码逻辑
 * @see ModbusThingModel.Property#parse(byte[]) 字节数据解析
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseDataPackage extends DataPackage {

    /**
     * 【MBAP Header】- Modbus TCP 帧头（7 字节）
     *
     * 包含字段：
     *  - transactionId (2 bytes)：事务 ID，与请求帧匹配
     *  - protocolId (2 bytes)：协议标识，固定 0x0000
     *  - length (2 bytes)：后续字节数（UnitID + PDU 长度）
     *  - unitId (1 byte)：从机地址
     *
     * 通过 MbapHeader.decode() 从原始字节解析。
     */
    MbapHeader header;

    /**
     * 【Modbus PDU】- 协议数据单元
     *
     * 根据 functionCode 不同，实际类型为：
     *  - ReadCoilsResponse（功能码 0x01）
     *  - ReadDiscreteInputsResponse（功能码 0x02）
     *  - ReadHoldingRegistersResponse（功能码 0x03）
     *  - ReadInputRegistersResponse（功能码 0x04）
     *
     * 通过 ModbusResponseDecoder.decode() 自动根据功能码反序列化。
     */
    ModbusPdu pdu;
}
