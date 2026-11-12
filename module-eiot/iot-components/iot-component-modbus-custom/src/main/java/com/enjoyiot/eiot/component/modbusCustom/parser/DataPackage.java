package com.enjoyiot.eiot.component.modbusCustom.parser;


/**
 * ============================================================================
 * 【数据包抽象基类】- 自定义协议包类型的统一父类
 *
 * 【协议包类型层次】
 *
 *  DataPackage (抽象基类)
 *    ├── RegisterDataPackage   — 设备注册包（r_{productKey}_{dn}_{slaveId}）
 *    ├── HeartbeatDataPackage  — 心跳包（5 字节 ASCII，内容为 dn）
 *    ├── RequestDataPackage    — Modbus 读命令请求包（编码前）
 *    └── ResponseDataPackage   — Modbus 响应包（MBAP Header + PDU）
 *
 * ============================================================================
 * 【为什么需要自定义协议包类型？】
 *
 *  因为"有人云 USR-G770 数传终端"在标准 Modbus TCP 之上叠加了自定义协议层：
 *
 *  标准 Modbus TCP 格式（仅 7 字节 MBAP + PDU）：
 *    [TransactionID][ProtocolID][Length][UnitID][FunctionCode][Data...]
 *
 *  USR-G770 自定义格式（三种包混在一起）：
 *    1. 注册包：r_{productKey}_{dn}_{slaveId}（ASCII 文本）
 *    2. 心跳包：5 字节 ASCII（dn 的 5 位序列号）
 *    3. 响应包：标准 Modbus TCP 帧（>7 字节）
 *
 *  需要通过 DataDecoder.decode() 根据包格式特征自动区分这三种类型。
 *
 * ============================================================================
 * 【数据流向】
 *
 *  上行（网关→平台）：
 *    网关 TCP 帧 → DataDecoder.decode() → DataPackage 子类
 *      → ModbusComponent.handle() switch 分发处理
 *
 *  下行（平台→网关）：
 *    readTask 构造 RequestDataPackage
 *      → DataEncoder.encode() → Modbus TCP 帧 → 发送到网关
 */
public abstract class DataPackage {
}
