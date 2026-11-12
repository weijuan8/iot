package com.enjoyiot.eiot.component.modbusCustom.parser;

import com.digitalpetri.modbus.ModbusPdu;
import com.digitalpetri.modbus.codec.MbapHeader;
import com.digitalpetri.modbus.codec.ModbusResponseDecoder;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

/**
 * ============================================================================
 * 【自定义协议数据解码器】- 识别并解析三种数据包类型
 *
 * 【职责】
 *  从 TCP 原始字节流中识别数据包类型，并解析为对应的 DataPackage 子类。
 *
 * ============================================================================
 * 【自定义协议层说明】
 *
 *  "有人云 USR-G770 数传终端"在标准 Modbus TCP 之上叠加了自定义协议层。
 *  网关通过同一个 TCP 连接发送三种不同格式的数据包：
 *
 *  ┌────────────────────┬────────────────────────────────┬──────────────────┐
 *  │ 数据包类型          │ 格式特征                        │ 解析结果          │
 *  ├────────────────────┼────────────────────────────────┼──────────────────┤
 *  │ 注册包             │ 以 "r_" 开头                    │ RegisterDataPackage│
 *  │                    │ 格式：r_{pk}_{dn}_{slaveId}     │                  │
 *  ├────────────────────┼────────────────────────────────┼──────────────────┤
 *  │ 心跳包             │ 恰好 5 字节                     │ HeartbeatDataPackage│
 *  │                    │ 内容：dn（设备序列号后5位）       │                  │
 *  ├────────────────────┼────────────────────────────────┼──────────────────┤
 *  │ Modbus 响应包       │ 大于 7 字节                     │ ResponseDataPackage│
 *  │                    │ 标准 Modbus TCP 帧              │ (MBAP+Pdu)       │
 *  └────────────────────┴────────────────────────────────┴──────────────────┘
 *
 * ============================================================================
 * 【解码流程（decode 方法）】
 *
 *  输入：原始 TCP 字节流 (Buffer)
 *    │
 *    ├─① 尝试解析为 ASCII 字符串
 *    │
 *    ├─② 以 "r_" 开头？
 *    │   ├─ YES → 按 "_" 分割 → 提取 pk, dn, slaveId → RegisterDataPackage
 *    │
 *    ├─③ 长度 == 5？
 *    │   ├─ YES → ASCII 字符串作为 dn → HeartbeatDataPackage
 *    │
 *    ├─④ 长度 > 7？
 *    │   ├─ YES → 前 7 字节 → MbapHeader.decode()
 *    │   │        第 8 字节起 → ModbusResponseDecoder.decode()
 *    │   │        → ResponseDataPackage(header, pdu)
 *    │
 *    └─⑤ 以上都不满足 → 返回 null（无法识别）
 *
 * ============================================================================
 * 【判断顺序的重要性】
 *
 *  必须严格按照"注册包 → 心跳包 → 响应包"的顺序判断：
 *
 *  - 如果先判断长度 > 7，注册包 "r_prod_001_A001_1"（17 字节）会被误判为响应包
 *  - 因此先检查 "r_" 前缀，排除注册包
 *  - 再检查长度 5，排除心跳包
 *  - 最后才检查长度 > 7，判定为 Modbus 响应包
 *
 * @author lzh
 * @see DataEncoder 请求帧编码器
 * @see com.enjoyiot.eiot.component.modbusCustom.service.ModbusComponent#handle(io.vertx.core.net.NetSocket) 业务处理
 */
@Slf4j
public class DataDecoder {

    /**
     * Modbus 响应解码器（digitalpetri 库）
     *
     * 负责将 Modbus PDU 字节序列反序列化为对应的 Java 对象：
     *  - ReadCoilsResponse
     *  - ReadDiscreteInputsResponse
     *  - ReadHoldingRegistersResponse
     *  - ReadInputRegistersResponse
     *
     * 根据 Function Code 自动选择合适的 Response 类型。
     */
    private static final ModbusResponseDecoder decoder = new ModbusResponseDecoder();

    /**
     * 【解码 TCP 原始数据】
     *
     * 根据自定义协议规则识别数据包类型并解析。
     *
     * 三种数据包格式：
     *  1. 注册包：r_{productKey}_{dn}_{slaveId}
     *     示例：r_prod_001_A001_1
     *
     *  2. 心跳包：dn（5 字节 ASCII）
     *     示例：A001（设备序列号后 5 位）
     *
     *  3. 响应包：Modbus TCP 帧（>7 字节）
     *     前 7 字节 = MBAP Header
     *     后续字节 = Modbus PDU
     *
     * @param buffer TCP 原始字节数据
     * @return 解析后的 DataPackage，无法识别返回 null
     */
    public static DataPackage decode(Buffer buffer) {
        /**
         * 其完整包格式为：
         * 1.注册包：r_{productKey}_{dn}_{slaveId}
         * 2.心跳包：dn
         * 3.响应包：modbus-tcp协议包
         * dn为设备序列号后5位
         */

        // 将字节数据转为 ASCII 字符串，用于判断注册包和心跳包
        String asciiStr = buffer.toString("US-ASCII");

        // ========== 判断 1：注册包（以 "r_" 开头）==========
        // 格式：r_{productKey}_{dn}_{slaveId}
        // 示例：r_prod_001_A001_1
        // 分割后：["r", "prod_001", "A001", "1"]
        //          [0]      [1]        [2]    [3]
        if (asciiStr.startsWith("r_")) {
            // 按 "_" 分割注册包字符串
            String[] split = asciiStr.split("_");
            if (split.length != 4) {
                // 格式不正确，必须恰好 4 段
                return null;
            }
            RegisterDataPackage registerData = new RegisterDataPackage();
            registerData.setProductKey(split[1]);  // 产品 Key
            registerData.setDn(split[2]);           // 设备序列号
            registerData.setSlaveId(Byte.parseByte(split[3])); // Modbus 从机地址
            return registerData;
        }

        // ========== 判断 2：心跳包（恰好 5 字节）==========
        // 5 字节 ASCII 字符串，内容为设备序列号（dn）
        // 与 Modbus TCP 最小帧（7 字节 MBAP Header）形成区分
        if (buffer.length() == 5) {
            HeartbeatDataPackage heartbeatData = new HeartbeatDataPackage();
            heartbeatData.setDn(asciiStr); // 整个 ASCII 字符串即为 dn
            return heartbeatData;
        }

        // ========== 判断 3：Modbus 响应包（>7 字节）==========
        // 标准 Modbus TCP 帧格式：
        //   前 7 字节 = MBAP Header（TransactionID + ProtocolID + Length + UnitID）
        //   后续字节 = Modbus PDU（FunctionCode + Data）
        if (buffer.length() > 7) {
            ResponseDataPackage responseData = new ResponseDataPackage();
            try {
                // 解析 MBAP Header（前 7 字节）
                // 提取 Transaction ID、Protocol ID、Length、Unit ID
                MbapHeader header = MbapHeader.decode(buffer.getBuffer(0, 7).getByteBuf());
                responseData.setHeader(header);

                // 解析 Modbus PDU（第 8 字节开始到末尾）
                // 根据 Function Code 自动反序列化为对应的 Response 类型
                //   0x01 → ReadCoilsResponse
                //   0x02 → ReadDiscreteInputsResponse
                //   0x03 → ReadHoldingRegistersResponse
                //   0x04 → ReadInputRegistersResponse
                ModbusPdu pdu = decoder.decode(buffer.getBuffer(7, buffer.length()).getByteBuf());
                responseData.setPdu(pdu);
            } catch (Exception e) {
                log.error("解码失败", e);
            }
            return responseData;
        }

        // 无法识别该数据包格式
        return null;
    }

}
