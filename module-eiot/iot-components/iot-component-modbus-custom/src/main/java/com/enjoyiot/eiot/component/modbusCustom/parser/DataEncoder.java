package com.enjoyiot.eiot.component.modbusCustom.parser;

import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

/**
 * ============================================================================
 * 【Modbus TCP 请求帧编码器】- 将 RequestDataPackage 编码为 Modbus TCP 帧
 *
 * 【职责】
 *  将 Java 对象（RequestDataPackage）序列化为标准 Modbus TCP 请求帧字节序列。
 *
 * ============================================================================
 * 【Modbus TCP 请求帧格式（完整结构）】
 *
 *  ┌───────────────────────────────────────────────────────────────────┐
 *  │ Byte Offset │ Field              │ Size    │ Value                │
 *  ├─────────────┼────────────────────┼─────────┼──────────────────────┤
 *  │ 0-1         │ Transaction ID     │ 2 bytes │ 事务标识符（1-999）    │
 *  │ 2-3         │ Protocol ID        │ 2 bytes │ 协议标识（固定 0x0000）│
 *  │ 4-5         │ Length             │ 2 bytes │ 后续字节数（固定 6）    │
 *  │ 6           │ Unit ID            │ 1 byte  │ 从机地址（slaveId）     │
 *  ├─────────────┼────────────────────┼─────────┼──────────────────────┤
 *  │ 7           │ Function Code      │ 1 byte  │ 功能码               │
 *  │ 8-9         │ Starting Address   │ 2 bytes │ 寄存器起始地址        │
 *  │ 10-11       │ Quantity           │ 2 bytes │ 寄存器数量            │
 *  └─────────────┴────────────────────┴─────────┴──────────────────────┘
 *
 *  总长度：12 字节（MBAP Header 7 字节 + PDU 5 字节）
 *
 * ============================================================================
 * 【编码示例】
 *
 *  输入：RequestDataPackage{
 *    transactionId=5, slaveId=1, functionCode=3,
 *    address=0, quantity=2
 *  }
 *
 *  输出（十六进制）：
 *    00 05  00 00  00 06  01    03  00 00  00 02
 *    ├─TX ID─┤├─PID─┤├─Len─┤├─UID─┤├─FC─┤├─Addr─┤├─Qty─┤
 *    MBAP Header (7 bytes)       PDU (5 bytes)
 *
 *  含义：向从机地址 1 发送命令，读取保持寄存器地址 0 开始的 2 个寄存器
 *
 * ============================================================================
 * 【与 DataDecoder 的对应关系】
 *
 *  发送方向：RequestDataPackage → DataEncoder.encode() → Buffer → Socket
 *  接收方向：Socket → Buffer → DataDecoder.decode() → ResponseDataPackage
 *
 * @author lzh
 * @see DataDecoder 响应包解码器
 * @see RequestDataPackage 请求包对象
 */
@Slf4j
public class DataEncoder {

    /**
     * 【编码 RequestDataPackage 为 Modbus TCP 请求帧】
     *
     * 将 Java 对象序列化为 12 字节的标准 Modbus TCP 请求帧。
     *
     * 调用链：
     *  readTask → 构造 RequestDataPackage → DataEncoder.encode(requestData)
     *    → Buffer → client.sendMessage(buffer) → socket.write() → 网关
     *
     * @param data 请求数据包（含 slaveId、transactionId、functionCode、address、quantity）
     * @return 12 字节的 Modbus TCP 请求帧 Buffer
     */
    public static Buffer encode(RequestDataPackage data) {
        Buffer buffer = Buffer.buffer();

        // ========== MBAP Header（7 字节）==========

        // 字节 0-1：事务标识符（Transaction ID），2 字节
        // 作用：匹配请求与响应，由 client.getTransactionId() 生成（1-999）
        buffer.appendShort(data.getTransactionId());

        // 字节 2-3：协议标识符（Protocol ID），2 字节
        // Modbus TCP 协议固定为 0x0000
        buffer.appendShort((short) 0);

        // 字节 4-5：长度字段（Length），2 字节
        // 表示后续 PDU 部分的字节数（从 Unit ID 到数据结束）
        // 本编码器固定为 6（UnitID 1 字节 + FunctionCode 1 字节 + Address 2 字节 + Quantity 2 字节）
        buffer.appendShort((short) 6);

        // 字节 6：单元标识符（Unit ID），1 字节
        // Modbus 从机地址，对应 RS485/RS232 总线上的设备地址
        buffer.appendByte(data.getSlaveId());

        // ========== Modbus PDU（5 字节）==========

        // 字节 7：功能码（Function Code），1 字节
        // 0x01=读线圈, 0x02=读离散输入, 0x03=读保持寄存器, 0x04=读输入寄存器
        buffer.appendByte(data.getFunctionCode());

        // 字节 8-9：寄存器起始地址（Starting Address），2 字节
        // 从物模型属性中获取：property.getRegAddr()
        buffer.appendShort(data.getAddress());

        // 字节 10-11：寄存器数量（Quantity of Registers），2 字节
        // 从物模型属性中获取：property.getRegNum()
        buffer.appendShort(data.getQuantity());

        return buffer;
    }
}
