package com.enjoyiot.eiot.component.modbusCustom.cilent;

import com.enjoyiot.eiot.common.utils.HexUtil;
import com.enjoyiot.module.eiot.api.modbus.dto.ModbusThingModel;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ============================================================================
 * 【Modbus TCP 客户端封装】- 网关连接的抽象表示
 *
 * 【职责】
 *  封装一个 Modbus 网关的 TCP 连接（NetSocket），管理：
 *  1. Socket 生命周期（建立、收发数据、关闭）
 *  2. Transaction ID 管理（1-999 循环递增，用于匹配请求与响应）
 *  3. 心跳检测（Socket 层面的活跃状态判断）
 *  4. 事务映射（transactionId → Property，响应匹配）
 *
 * ============================================================================
 * 【与网关的对应关系】
 *
 *  1 个物理网关 = 1 个 TCP 连接 = 1 个 VertxModbusClient 实例
 *
 *  一个网关可能挂载多个 Modbus 从机设备（通过不同的 slaveId 区分），
 *  但它们的 TCP 连接共用同一个 NetSocket。
 *
 *  ┌─────────────────┐
 *  │ USR-G770 网关    │
 *  │  ├─ slaveId=1   │──(TCP 连接)──> VertxModbusClient
 *  │  ├─ slaveId=2   │
 *  │  └─ slaveId=3   │
 *  └─────────────────┘
 *
 * ============================================================================
 * 【Transaction ID 机制】
 *
 *  Modbus TCP 协议通过 Transaction ID 匹配请求和响应：
 *
 *  请求帧  MBAP Header: [TransactionID=5][ProtocolID=0][Length=6][UnitID=1]
 *                       PDU: [FunctionCode=3][Address][Quantity]
 *  响应帧  MBAP Header: [TransactionID=5][ProtocolID=0][Length=N][UnitID=1]
 *                       PDU: [FunctionCode=3][ByteCount][RegisterValues...]
 *
 *  当网关同时收到多个读取命令时，通过 Transaction ID 区分每个命令的响应。
 *
 *  Transaction ID 范围：1-999（循环递增）
 *  getTransactionId() 实现：return (short) transactionId.updateAndGet(i -> (i % 999) + 1);
 *
 * ============================================================================
 * 【事务映射表 (transactionMap)】
 *
 *  Key: transactionId (Short) — 请求帧的事务 ID
 *  Value: ModbusThingModel.Property — 物模型属性定义（含寄存器类型、地址、数据类型等）
 *
 *  作用：
 *  1. 发送命令时：将 (transactionId → property) 存入映射表
 *  2. 收到响应时：根据 transactionId 从映射表中取出 property
 *     → 调用 property.parse() 将原始字节转为业务值
 *
 *  这解决了"收到 Modbus 响应时，如何知道该用哪种规则解析数据"的问题。
 *
 * @see com.enjoyiot.eiot.component.modbusCustom.service.ModbusComponent 核心组件
 * @see ModbusThingModel.Property 物模型属性定义
 */
@Slf4j
public class VertxModbusClient {

    /**
     * 客户端唯一 ID（UUID + 远程地址）
     * 用于日志追踪
     */
    @Getter
    private String id;

    /**
     * 【设备序列号 (Device Name)】
     *
     * 来源：网关注册包 `r_{productKey}_{dn}_{slaveId}` 中的 dn 字段
     * 例如：r_prod_001_A001_1 → dn = "A001"
     *
     * 初始值：由 ModbusComponent.handle() 设置，初始设为 clientId（UUID）
     * 后续：收到注册包后更新为真实的 dn
     */
    @Getter
    @Setter
    private String dn;

    /**
     * 产品 Key (Product Key)
     *
     * 来源：网关注册包中的 productKey 字段
     * 用于查找该产品的物模型定义（属性、寄存器地址等）
     */
    @Getter
    @Setter
    private String productKey;

    /**
     * 【Modbus 从机地址 (Slave ID)】
     *
     * 来源：网关注册包中的 slaveId 字段
     *
     * 在 Modbus 协议中，每个从机设备有一个唯一的地址（1-247）。
     * 当网关下挂多个 Modbus 设备时，通过 slaveId 区分。
     *
     * 注意：slaveId 在 MBAP Header 的 Unit ID 字段中使用。
     * 每个网关的 TCP 连接可能有多个从机，但在这个组件实现中，
     * 每个网关只有一个主从机地址（从注册包中提取）。
     */
    @Getter
    @Setter
    private Byte slaveId;

    /**
     * 【TCP Socket 连接】
     *
     * 与网关之间的 TCP 连接对象。
     * 通过 setSocket() 设置，同时注册三个 Handler：
     *  - exceptionHandler：异常处理
     *  - closeHandler：连接关闭处理
     *  - handler：数据接收处理（核心）
     */
    public NetSocket socket;

    /**
     * 【心跳超时时间（毫秒）】
     *
     * 默认 60 秒。
     * Socket 层面的超时检测：如果超过此时间没有收到任何数据，
     * isOnline() 返回 false，offlineCheckTask 将关闭此连接。
     *
     * 注意：这与 ModbusComponent.keepAliveTimeout 不同：
     *  - 本字段用于 Socket 层面的"是否还活着"判断
     *  - ModbusComponent.keepAliveTimeout 用于业务层面的"心跳超时离线"判断
     */
    @Setter
    private long keepAliveTimeoutMs = Duration.ofSeconds(60).toMillis();

    /**
     * 【最后一次活跃时间（毫秒时间戳）】
     *
     * 每次收到数据（handler 回调）时更新。
     * 用于 isOnline() 判断 Socket 是否超时。
     */
    private volatile long lastKeepAliveTime = System.currentTimeMillis();

    /**
     * 【Transaction ID 生成器】
     *
     * 原子计数器，1-999 循环递增。
     * 每次发送 Modbus 读取命令时调用 getTransactionId() 获取新 ID。
     *
     * 范围选择 1-999 的原因：
     *  - Transaction ID 在 MBAP Header 中占 2 字节（0-65535）
     *  - 但实际场景中，网关短时间内收到的并发命令不会超过几百个
     *  - 使用较小范围方便调试和日志追踪
     */
    private final AtomicInteger transactionId = new AtomicInteger(0);

    /**
     * 【事务映射表】- Transaction ID → 物模型属性
     *
     * 核心数据结构，连接"发送请求"与"解析响应"两个阶段：
     *
     * 发送时：
     *   client.getTransactionMap().put(requestData.getTransactionId(), property);
     *
     * 收到响应时：
     *   ModbusThingModel.Property property = client.getTransactionMap().remove(transactionId);
     *   if (property == null) return; // 找不到对应属性，忽略
     *   Object value = property.parse(bytes); // 按属性定义解析数据
     *
     * 使用 ConcurrentHashMap 保证并发安全（readTask 多线程发送命令）。
     */
    @Getter
    private final Map<Short, ModbusThingModel.Property> transactionMap = new ConcurrentHashMap<>();

    /**
     * 【数据消费者回调】
     *
     * 由 ModbusComponent.handle() 设置，指向 Lambda 表达式 bufferConsumer。
     *
     * 当 Socket 收到数据时：
     *   socket.handler(buffer -> {
     *       keepAlive();         // 更新活跃时间
     *       consumer.accept(buffer); // → DataDecoder.decode() → 注册/心跳/响应处理
     *   });
     */
    @Setter
    private Consumer<Buffer> consumer;

    /**
     * 【构造函数】
     *
     * @param id 客户端唯一标识（UUID + 远程地址）
     */
    public VertxModbusClient(String id) {
        this.id = id;
    }

    /**
     * 【更新 Socket 层活跃时间】
     *
     * 每次收到数据或发送消息成功时调用。
     * 配合 isOnline() 用于 Socket 层的超时检测。
     */
    public void keepAlive() {
        lastKeepAliveTime = System.currentTimeMillis();
    }

    /**
     * 【判断 Socket 是否在线】
     *
     * 逻辑：当前时间 - 最后活跃时间 < 超时阈值
     *
     * 如果返回 false，offlineCheckTask 会调用 shutdown() 关闭此连接。
     *
     * @return true=连接活跃，false=连接超时
     */
    public boolean isOnline() {
        return System.currentTimeMillis() - lastKeepAliveTime < keepAliveTimeoutMs;
    }

    /**
     * 【设置 TCP Socket 并注册事件处理器】
     *
     * 这是客户端初始化的核心方法，建立 Socket 与业务逻辑的绑定。
     *
     * 执行流程：
     *  1. 线程安全地替换旧 Socket（如果存在）
     *  2. 注册三个事件处理器：
     *
     *     a. exceptionHandler：异常处理
     *        - 打印异常堆栈，防止异常导致连接泄漏
     *
     *     b. closeHandler：连接关闭处理
     *        - 网关主动断开或网络异常时触发
     *        - 调用 shutdown() 清理资源
     *
     *     c. handler：数据接收处理（核心）
     *        - 收到网关发送的 TCP 数据
     *        - 更新活跃时间（keepAlive）
     *        - 回调 consumer.accept(buffer) 进入业务处理
     *        - 防内存泄漏检查：如果 Socket 已被替换，关闭旧 Socket
     *
     * 数据流向：
     *  网关 TCP 帧 → NetSocket.handler(buffer)
     *    → keepAlive() 更新心跳
     *    → consumer.accept(buffer)
     *      → DataDecoder.decode(buffer) 解析数据包类型
     *        → RegisterDataPackage / HeartbeatDataPackage / ResponseDataPackage
     *
     * @param socket Vert.x NetSocket 连接
     */
    public void setSocket(NetSocket socket) {
        synchronized (this) {
            // 如果已有旧 Socket，先关闭（防止资源泄漏）
            if (this.socket != null && this.socket != socket) {
                this.socket.close();
            }

            // 设置 Socket 并注册事件处理器
            this.socket = socket
                    // 异常处理器：打印堆栈，防止异常被静默吞掉
                    .exceptionHandler(Throwable::printStackTrace)
                    // 连接关闭处理器：网关断开或网络异常
                    .closeHandler(v -> {
                        log.debug("modbus client[{}] close", dn);
                        shutdown(); // 清理资源
                    })
                    // 数据接收处理器（核心回调）
                    .handler(buffer -> {
                        if (log.isDebugEnabled()) {
                            log.debug("handle modbus client[{}] payload:[{}]",
                                    dn,
                                    HexUtil.toFormattedHexString(buffer.getBytes()));
                        }
                        // 更新 Socket 层活跃时间
                        keepAlive();
                        // 将数据交给业务消费者处理
                        // consumer 即 ModbusComponent.handle() 中定义的 bufferConsumer
                        consumer.accept(buffer);
                        // 防内存泄漏：如果 Socket 已被新连接替换，关闭旧 Socket
                        if (this.socket != socket) {
                            log.warn("modbus client[{}] memory leak ", dn);
                            socket.close();
                        }
                    });
        }
    }

    /**
     * 【关闭客户端连接】
     *
     * 线程安全地关闭 Socket 并清理引用。
     *
     * 调用时机：
     *  - 网关主动断开连接（closeHandler 回调）
     *  - offlineCheckTask 检测到 Socket 超时
     *  - stateChange(false) 停用组件时
     *  - 未注册的设备连接（ModbusComponent.handle() 末尾检查）
     */
    public void shutdown() {
        log.debug("modbus client[{}] disconnect", dn);
        synchronized (this) {
            if (null != socket) {
                execute(socket::close); // 安全关闭
                this.socket = null;
            }
        }
    }

    /**
     * 【发送 Modbus 消息给网关】
     *
     * 通过 TCP Socket 将 Modbus TCP 帧发送给网关。
     *
     * 发送流程：
     *  readTask → DataEncoder.encode(RequestDataPackage) → Buffer
     *    → client.sendMessage(buffer)
     *      → socket.write(buffer, callback)
     *        → 网关收到 Modbus TCP 帧
     *          → 网关解析 MBAP Header → 提取 PDU
     *            → 转发给 Modbus 从机（通过 RS485/RS232）
     *              → 从机返回响应 → 网关封装为 TCP 帧 → 发回平台
     *
     * 线程安全：通过 synchronized 保护 socket 引用。
     *
     * @param buffer Modbus TCP 帧数据（MBAP Header + PDU）
     */
    public void sendMessage(Buffer buffer) {
        NetSocket socket;
        synchronized (this) {
            socket = this.socket;
            if (socket == null) {
                return; // Socket 已关闭，不发送
            }
        }
        log.debug("write data:{}", HexUtil.toFormattedHexString(buffer.getBytes()));
        // 异步写入 Socket（非阻塞）
        socket.write(buffer, r -> {
            // 发送成功或失败都更新活跃时间（说明连接仍然可用）
            keepAlive();
            if (r.succeeded()) {
                log.info("client[{}] msg send success:{}", dn, HexUtil.toFormattedHexString(buffer.getBytes()));
            } else {
                log.error("client[{}] msg send failed", dn, r.cause());
            }
        });
    }

    /**
     * 【安全执行 Runnable】
     *
     * 捕获异常，防止 Socket 关闭时的异常导致线程中断。
     */
    private void execute(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("close modbus client error", e);
        }
    }

    /**
     * 【获取下一个 Transaction ID】
     *
     * 原子操作，1-999 循环递增。
     *
     * 实现细节：
     *  transactionId.updateAndGet(i -> (i % 999) + 1)
     *  - i=0   → 1
     *  - i=1   → 2
     *  - ...
     *  - i=999 → 1（循环）
     *
     * 使用 AtomicInteger 保证并发安全（多个 readTask 线程可能同时获取 ID）。
     *
     * @return Transaction ID（1-999）
     */
    public short getTransactionId() {
        // 1-999 循环递增
        return (short) transactionId.updateAndGet(i -> (i % 999) + 1);
    }
}
