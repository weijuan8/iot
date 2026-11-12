package com.enjoyiot.eiot.component.modbusCustom.parser;

import lombok.Data;

/**
 * ============================================================================
 * 【心跳包】- 网关定期发送的存活信号
 *
 * 【包格式】
 *  原始数据：5 字节 ASCII 字符串
 *  示例：    A001
 *
 *  内容：dn（设备序列号）
 *
 * ============================================================================
 * 【解析逻辑】（DataDecoder.decode()）
 *
 *  判断条件：buffer.length() == 5（非注册包，且恰好 5 字节）
 *  → 直接读取 ASCII 字符串作为 dn
 *
 * ============================================================================
 * 【处理逻辑】（ModbusComponent.handle()）
 *
 *  1. 提取 dn
 *  2. 调用 online(dn)
 *     → heartbeatDevice.put(dn, System.currentTimeMillis()) 记录心跳时间
 *     → report(DeviceStateChange.ONLINE) 上报设备上线
 *
 * ============================================================================
 * 【为什么是 5 字节？】
 *
 *  这是"有人云 USR-G770 数传终端"的自定义心跳格式。
 *  网关取设备序列号（dn）的后 5 位作为心跳包内容。
 *
 *  5 字节的判断条件与 Modbus TCP 最小帧（7 字节 MBAP Header）形成区分：
 *  - 5 字节 → 心跳包
 *  - >7 字节 → Modbus 响应包
 *
 * @see DataDecoder 解码逻辑
 * @see com.enjoyiot.eiot.component.modbusCustom.service.ModbusComponent#online(String) 上线处理
 */
@Data
public class HeartbeatDataPackage extends DataPackage {

    /**
     * 设备序列号（Device Name）
     * 心跳包内容即为 dn（5 位 ASCII）
     */
    private String dn;
}
