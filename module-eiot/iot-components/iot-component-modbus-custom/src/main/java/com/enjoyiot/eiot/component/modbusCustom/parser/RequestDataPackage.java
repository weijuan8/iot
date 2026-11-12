package com.enjoyiot.eiot.component.modbusCustom.parser;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 【Modbus 读命令请求包】- 平台发送给网关的读取寄存器命令
 *
 * 【包结构】
 *  此对象是编码前的 Java 对象表示，通过 DataEncoder.encode() 编码为
 *  标准 Modbus TCP 帧（MBAP Header + PDU）后发送。
 *
 * ============================================================================
 * 【字段与 Modbus TCP 帧的对应关系】
 *
 *  ┌─────────────────────────────────────────────────────────────────┐
 *  │ MBAP Header (7 bytes)                                           │
 *  │  [TransactionID][ProtocolID][Length][UnitID]                     │
 *  │   2 bytes       2 bytes    2 bytes 1 byte                       │
 *  ├─────────────────────────────────────────────────────────────────┤
 *  │ PDU                                                             │
 *  │  [FunctionCode][StartingAddress][QuantityOfRegisters]            │
 *  │   1 byte       2 bytes           2 bytes                        │
 *  └─────────────────────────────────────────────────────────────────┘
 *
 *  请求帧示例（读取保持寄存器，地址 0，数量 2）：
 *    Transaction ID: 0x0001
 *    Protocol ID:    0x0000 (固定)
 *    Length:         0x0006 (后续 6 字节)
 *    Unit ID:        0x01   (从机地址)
 *    Function Code:  0x03   (读保持寄存器)
 *    Start Address:  0x0000 (寄存器起始地址)
 *    Quantity:       0x0002 (读取 2 个寄存器)
 *
 * ============================================================================
 * 【功能码说明（Modbus 标准）】
 *
 *  ┌──────────────┬──────┬──────────────────────────────────────────┐
 *  │ 功能码       │ 数值  │ 说明                                      │
 *  ├──────────────┼──────┼──────────────────────────────────────────┤
 *  │ Read Coils   │ 01   │ 读线圈状态（位操作，可读可写）            │
 *  │ Read Discrete │ 02  │ 读离散输入（位操作，只读）                │
 *  │ Read Holding │ 03   │ 读保持寄存器（16位，可读可写，最常用）    │
 *  │ Read Input   │ 04   │ 读输入寄存器（16位，只读，如传感器读数）  │
 *  └──────────────┴──────┴──────────────────────────────────────────┘
 *
 *  功能码来自物模型属性定义：property.getRegType() = "01" / "02" / "03" / "04"
 *
 * @see DataEncoder 编码为 Modbus TCP 帧
 * @see ModbusThingModel.Property 物模型属性定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestDataPackage extends DataPackage {

    /**
     * 【Modbus 从机地址 (Slave ID)】
     *
     * 对应 MBAP Header 的 Unit ID 字段（1 字节）。
     * 取值 1-247，标识 RS485/RS232 总线上的具体从机设备。
     *
     * 来源：网关注册包中的 slaveId 字段。
     */
    private byte slaveId;

    /**
     * 【事务标识符 (Transaction ID)】
     *
     * 对应 MBAP Header 的 Transaction ID 字段（2 字节）。
     *
     * 作用：匹配请求与响应。
     *  - 发送时：client.getTransactionMap().put(transactionId, property)
     *  - 收到响应时：从 transactionMap 取出 property 用于解析数据
     *
     * 生成：client.getTransactionId()，1-999 循环递增。
     */
    private short transactionId;

    /**
     * 【功能码 (Function Code)】
     *
     * 对应 PDU 的第一个字节（1 字节）。
     *
     * 来源：property.getRegType()（物模型属性中配置的寄存器类型）
     *  - "01" → 0x01 Read Coils（读线圈）
     *  - "02" → 0x02 Read Discrete Inputs（读离散输入）
     *  - "03" → 0x03 Read Holding Registers（读保持寄存器）
     *  - "04" → 0x04 Read Input Registers（读输入寄存器）
     */
    private byte functionCode;

    /**
     * 【寄存器起始地址 (Starting Address)】
     *
     * 对应 PDU 的 Starting Address 字段（2 字节）。
     *
     * 来源：property.getRegAddr()（物模型属性中配置的寄存器地址）
     * 例如：40001 表示保持寄存器的第 1 个地址
     *
     * 注意：Modbus 协议中，有些设备使用 0-based 地址，有些使用 1-based 地址，
     * 具体取决于设备厂商的实现。
     */
    private short address;

    /**
     * 【寄存器数量 (Quantity)】
     *
     * 对应 PDU 的 Quantity of Registers 字段（2 字节）。
     *
     * 来源：property.getRegNum()（物模型属性中配置的寄存器数量）
     *
     * 例如：
     *  - 读取一个 16 位整数：quantity = 1（1 个寄存器 = 2 字节）
     *  - 读取一个 32 位浮点数：quantity = 2（2 个寄存器 = 4 字节）
     *  - 读取一个字符串：quantity = N（取决于字符串长度）
     *
     * 不同功能码的最大读取数量：
     *  - 线圈/离散输入：最多 2000 个
     *  - 保持/输入寄存器：最多 125 个
     */
    private short quantity;
}
