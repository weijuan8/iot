
package com.enjoyiot.eiot.component.modbusCustom.service;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.IdUtil;
import com.digitalpetri.modbus.ModbusPdu;
import com.digitalpetri.modbus.codec.MbapHeader;
import com.digitalpetri.modbus.responses.ReadCoilsResponse;
import com.digitalpetri.modbus.responses.ReadDiscreteInputsResponse;
import com.digitalpetri.modbus.responses.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.responses.ReadInputRegistersResponse;
import com.enjoyiot.eiot.common.enums.DeviceState;
import com.enjoyiot.eiot.common.utils.HexUtil;
import com.enjoyiot.eiot.component.core.ComponentServices;
import com.enjoyiot.eiot.component.core.ThingComponent;
import com.enjoyiot.eiot.component.core.model.down.*;
import com.enjoyiot.eiot.component.core.model.up.DeviceStateChange;
import com.enjoyiot.eiot.component.core.model.up.PropertyReport;
import com.enjoyiot.eiot.component.modbusCustom.cilent.VertxModbusClient;
import com.enjoyiot.eiot.component.modbusCustom.model.ModbusConfig;
import com.enjoyiot.eiot.component.modbusCustom.parser.*;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.RegisterDevice;
import com.enjoyiot.module.eiot.api.modbus.ModbusInfoApi;
import com.enjoyiot.module.eiot.api.modbus.ModbusThingModelApi;
import com.enjoyiot.module.eiot.api.modbus.dto.ModbusThingModel;
import com.enjoyiot.module.eiot.api.product.ProductApi;
import io.netty.buffer.ByteBuf;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * 【Modbus-TCP 自定义协议组件】- 工业设备采集的最佳实践示例
 *
 * ============================================================================
 * 【场景说明】
 * 本组件是为"有人云 USR-G770 数传终端"定制的 Modbus-TCP 协议适配器。
 * 数传终端（网关）通过 TCP 连接到此组件，将下层 Modbus 设备的数据透传过来。
 *
 * 【架构模式】
 *   Modbus 从机设备 ←→ 数传终端(网关/USR-G770) ←(TCP)→ ModbusComponent(本组件) ←→ 平台
 *
 *   特点：
 *    - 平台作为 Modbus 主机（Master），主动发送读取命令
 *    - 网关作为透传通道，将 Modbus RTU 封装为 TCP 帧转发
 *    - 组件维护每个网关/设备的 TCP 连接，定时轮询读取寄存器
 *
 * ============================================================================
 * 【与 MQTT/TCP 组件的关键区别】
 *
 *  ┌───────────────┬──────────────────┬──────────────────┬──────────────────┐
 *  │               │ MqttComponent    │ TcpComponent     │ ModbusComponent  │
 *  ├───────────────┼──────────────────┼──────────────────┼──────────────────┤
 *  │ 通讯方向       │ 双向（设备主动上报+平台下发）  │ 主动轮询（主机模式）    │
 *  │ 数据获取方式   │ 设备主动上报     │ 设备主动上报     │ 定时轮询读取寄存器  │
 *  │ 协议层次       │ MQTT 应用协议    │ 私有二进制协议   │ Modbus 工业协议    │
 *  │ 设备模式       │ 设备=客户端      │ 设备=客户端      │ 平台=Master       │
 *  │ 连接管理       │ 直连 MQTT Server │ 直连 TCP Server  │ 直连 TCP Server   │
 *  │ 数据解析       │ JSON 解析        │ 自定义二进制帧   │ Modbus PDU 解析   │
 *  └───────────────┴──────────────────┴──────────────────┴──────────────────┘
 *
 * ============================================================================
 * 【下行消息（平台→设备）的特殊性】
 *
 * Modbus 协议的特殊之处在于：
 *  - 常规的下行消息（serviceInvoke/propertySet 等）**全部为空实现**
 *  - 因为 Modbus 场景下平台是 Master，通过定时轮询（readTask）主动读取数据
 *  - 这与其他协议组件（MQTT/TCP 等设备主动上报）完全不同
 *
 * 如果你需要在 Modbus 上实现"属性设置"（如写寄存器），需要在
 * propertySet() 方法中编码 WriteSingleRegister 或 WriteMultipleRegisters 命令。
 *
 * ============================================================================
 * 【数据流详解】
 *
 *  1. 网关连接阶段：
 *    网关(USR-G770) → TCP 连接 → ModbusVerticle 接收
 *      → ModbusComponent.handle(NetSocket)
 *        → 创建 VertxModbusClient → 等待 RegisterDataPackage
 *          → 设备注册 → 缓存 clientMap + dnToDevice
 *            → 加载物模型(pkToThingModel)
 *
 *  2. 数据采集阶段（核心）：
 *    readTask 定时任务触发（间隔由 config.timer 决定）
 *      → 遍历所有 clientMap 中的设备
 *        → 查物模型属性列表
 *          → 对每个属性构造 RequestDataPackage（transactionId + slaveId + functionCode + address + quantity）
 *            → DataEncoder.encode() 编码为 Modbus TCP 帧
 *              → client.sendMessage() 发送到网关
 *                → 网关透传给 Modbus 从机
 *                  → 从机返回 ResponseDataPackage
 *                    → DataDecoder.decode() 解析响应
 *                      → 根据 functionCode 解析寄存器数据
 *                        → property.parse() 转换为业务值
 *                          → report(PropertyReport) 上报到平台
 *
 *  3. 心跳/离线检测：
 *    网关定期发送 HeartbeatDataPackage
 *      → online(dn) 记录心跳时间
 *    offlineCheckTask 定时任务（每 40 秒）
 *      → 检查心跳是否超时（keepAliveTimeout * 2）
 *        → 超时 → report(OFFLINE)
 *
 * ============================================================================
 * 【配置格式】
 *  {
 *    "port": 502,     // Modbus TCP 端口（默认 502）
 *    "timer": 10      // 轮询间隔（秒）
 *  }
 *
 * ============================================================================
 * 【扩展此组件的思路】
 * 如果想添加"属性设置"（写寄存器）：
 *   在 propertySet() 方法中：
 *   1. 从 action.params 获取要写入的值
 *   2. 查物模型获取寄存器地址
 *   3. 构造 WriteSingleRegister 或 WriteMultipleRegisters PDU
 *   4. DataEncoder.encode() → client.sendMessage() 发送
 *
 * @see ModbusVerticle TCP 服务管理
 * @see VertxModbusClient Modbus 客户端连接
 */
@Slf4j
@Component
public class ModbusComponent extends ThingComponent implements Handler<NetSocket> {

    /**
     * 客户端连接映射
     * Key: dn（设备名称/序列号）
     * Value: VertxModbusClient（Modbus 客户端连接对象）
     *
     * 用于查找设备对应的 TCP 连接，发送 Modbus 读取命令。
     */
    private final Map<String, VertxModbusClient> clientMap = new ConcurrentHashMap<>();

    /**
     * 设备名称 → 设备信息映射
     * Key: dn
     * Value: DeviceInfo（包含 deviceId、productKey 等）
     */
    private final Map<String, DeviceInfo> dnToDevice = new ConcurrentHashMap<>();

    /**
     * 心跳记录
     * Key: dn
     * Value: 最后一次心跳的时间戳（毫秒）
     *
     * offlineCheckTask 定时检查，超时则判定离线。
     */
    private final Map<String, Long> heartbeatDevice = new ConcurrentHashMap<>();

    /**
     * 产品物模型缓存
     * Key: productKey
     * Value: ModbusThingModel（该产品的属性定义，含寄存器地址、类型、数量等）
     *
     * 通过 ModbusThingModelApi 从数据库加载，首次访问时缓存。
     */
    private final Map<String, ModbusThingModel> pkToThingModel = new ConcurrentHashMap<>();

    /**
     * TCP 服务管理（Verticle 模式）
     * 负责 TCP Server 的启动/停止
     */
    private final ModbusVerticle modbusVerticle;

    private final ProductApi productApi;

    private final DeviceApi deviceApi;

    /**
     * Modbus 从机信息 API
     * 用于查询 Modbus 设备的基础信息（从机地址等）
     */
    private final ModbusInfoApi modbusInfoApi;

    /**
     * Modbus 物模型 API
     * 用于查询产品的属性定义（寄存器地址、数据类型等）
     */
    private final ModbusThingModelApi modbusThingModelApi;

    /**
     * 定时任务调度器
     * 管理 readTask（轮询读取）和 offlineCheckTask（离线检测）
     */
    private final ThreadPoolTaskScheduler taskScheduler;
    private ScheduledFuture<?> readTaskFuture;
    private ScheduledFuture<?> offlineCheckTaskFuture;


    /**
     * Vert.x 实例（用于创建 TCP Server）
     */
    private Vertx vertx;

    /**
     * TCP Server 实例
     */
    private NetServer server;

    /**
     * 当前 Modbus 配置
     */
    private ModbusConfig modbusConfig;

    /**
     * 心跳超时时间（默认 60 秒）
     * 超过此时间未收到心跳，判定设备离线。
     */
    @Setter
    private long keepAliveTimeout = Duration.ofSeconds(60).toMillis();



    /**
     * 【构造函数】
     *
     * 通过构造注入获取所有依赖。
     * ComponentServices 由 ThingComponent 传递到 AbstractComponent，
     * 提供 MQ、Redis、API 等基础设施。
     *
     * @param componentServices 平台基础设施聚合
     * @param modbusVerticle TCP Server 管理
     * @param productApi 产品信息查询
     * @param deviceApi 设备注册/查询
     * @param modbusInfoApi Modbus 从机信息
     * @param modbusThingModelApi 物模型属性定义
     * @param taskScheduler 定时任务调度器
     */
    protected ModbusComponent(
            ComponentServices componentServices,
            ModbusVerticle modbusVerticle,
            ProductApi productApi,
            DeviceApi deviceApi,
            ModbusInfoApi modbusInfoApi,
            ModbusThingModelApi modbusThingModelApi,
            @Qualifier("ModbusTaskScheduler") ThreadPoolTaskScheduler taskScheduler
    ) {
        super(componentServices);
        this.modbusVerticle = modbusVerticle;
        this.productApi = productApi;
        this.deviceApi = deviceApi;
        this.modbusInfoApi = modbusInfoApi;
        this.modbusThingModelApi = modbusThingModelApi;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 【设置 TCP Server】
     * 由 ModbusVerticle.startServer() 调用，将创建的 NetServer 注入进来。
     */
    public void setModbusServer(NetServer server, Vertx vertx){
        this.vertx = vertx;
        this.server = server;
        // 处理新的连接
        server.connectHandler(this);
    }



    /**
     * 【处理新 TCP 连接】- 网关/数传终端连接入口
     *
     * ============================================================================
     * 【完整流程】
     *
     * ① 收到 TCP 连接 → 创建 VertxModbusClient → 设置 Socket
     * ② 等待接收数据 → DataDecoder.decode() 解码
     * ③ 根据数据包类型处理：
     *    - RegisterDataPackage：设备注册（提取 dn、pk、slaveId）
     *    - HeartbeatDataPackage：心跳（更新心跳时间）
     *    - ResponseDataPackage：Modbus 响应数据（解析寄存器值）
     *
     * 【Modbus TCP 帧格式】
     *  MBAP Header (7 bytes):
     *    Transaction ID (2) + Protocol ID (2) + Length (2) + Unit ID (1)
     *  PDU:
     *    Function Code (1) + Data (N)
     *
     * 【数据解析流程】
     *  网关发送的 TCP 帧 → DataDecoder.decode()
     *    → 根据自定义协议头判断数据包类型
     *    → 如果是 ResponseDataPackage：提取 MBAP Header + Modbus PDU
     *      → 根据 Function Code 反序列化为对应 Response 类型
     *        → ReadCoils → ReadCoilsResponse
     *        → ReadDiscreteInputs → ReadDiscreteInputsResponse
     *        → ReadHoldingRegisters → ReadHoldingRegistersResponse
     *        → ReadInputRegisters → ReadInputRegistersResponse
     *      → 通过 transactionId 匹配之前发送的请求
     *      → 获取对应的物模型属性定义
     *      → property.parse() 将原始字节转换为业务值
     *      → report(PropertyReport) 上报到平台
     *
     * @param socket TCP Socket 连接
     */
    @Override
    public void handle(NetSocket socket) {
        log.info("收到客户端连接：{}", socket.remoteAddress());

        // ========== 阶段 1：创建客户端对象 ==========
        // 生成唯一 clientId（UUID + 远程地址），用于日志追踪
        String clientId = IdUtil.simpleUUID() + "_" + socket.remoteAddress();
        VertxModbusClient client = new VertxModbusClient(clientId);
        // 先设置临时 dn（等于 clientId），避免后续代码中 dn 为 null 报错
        // 收到注册包后会被更新为真实的 dn
        client.setDn(clientId);
        try {
            // 设置 Socket 层的超时检测时间
            client.setKeepAliveTimeoutMs(keepAliveTimeout);
            // 设置 Socket 并注册事件处理器（exceptionHandler + closeHandler + handler）
            // handler 中会回调 bufferConsumer
            client.setSocket(socket);

            // ========== 阶段 2：定义数据消费回调（bufferConsumer）==========
            // 当网关通过 TCP 发送数据时，Vert.x 事件循环会调用此 Lambda
            Consumer<Buffer> bufferConsumer = buffer -> {
                try {
                    // 日志：打印原始数据的十六进制和 ASCII 形式
                    log.info("[{}]解析数据:{} ASCII:{}", client.getDn(), HexUtil.toHexString(buffer.getBytes()), buffer.toString("US-ASCII"));

                    // 【关键】调用 DataDecoder.decode() 识别数据包类型
                    // 根据自定义协议规则，自动识别为注册包/心跳包/响应包
                    DataPackage data = DataDecoder.decode(buffer);
                    if (data == null) return; // 无法识别的数据包，忽略

                    // ========== 分支 A：设备注册包 ==========
                    // 格式：r_{productKey}_{dn}_{slaveId}
                    // 网关首次连接时发送，携带设备身份信息
                    if (data instanceof RegisterDataPackage) {
                        String dn = ((RegisterDataPackage) data).getDn();
                        // 更新 client 的 dn（从临时 UUID 替换为真实设备序列号）
                        client.setDn(dn);

                        // 如果该设备之前有心跳记录（可能是重连），清除旧记录
                        heartbeatDevice.remove(dn);
                        // 将 client 加入 clientMap（dn → client）
                        // 后续 readTask 可以通过 dn 找到此连接来发送读取命令
                        clientMap.put(dn, client);

                        String productKey = ((RegisterDataPackage) data).getProductKey();
                        Byte slaveId = ((RegisterDataPackage) data).getSlaveId();

                        // 更新 client 的产品和从机地址信息
                        client.setProductKey(productKey);
                        client.setSlaveId(slaveId);

                        // 【设备注册】调用平台 API 注册设备
                        // registerDevice() 内部逻辑：
                        //   - 如果设备不存在 → 创建新设备记录
                        //   - 如果设备已存在 → 返回已有记录
                        RegisterDevice build = RegisterDevice.builder()
                                .productKey(productKey)
                                .deviceName(dn)
                                .build();
                        DeviceInfo device = deviceApi.registerDevice(build);
                        // 缓存设备信息（dn → DeviceInfo）
                        dnToDevice.put(dn, device);

                        // 【加载物模型】首次访问时从数据库加载，后续从缓存读取
                        // computeIfAbsent：如果缓存中不存在，调用 modbusThingModelApi.findByProductKey() 从 DB 加载
                        // 物模型包含该产品的所有属性定义（寄存器类型、地址、数量、数据类型等）
                        ModbusThingModel thingModel = pkToThingModel.computeIfAbsent(
                                device.getProductKey(),
                                modbusThingModelApi::findByProductKey
                        );
                        if (thingModel == null) {
                            log.warn("thingModel not found for productKey: {}", device.getProductKey());
                            return;
                        }
                        return;
                    }

                    // ========== 分支 B：心跳包 ==========
                    // 格式：5 字节 ASCII，内容为 dn
                    // 网关定期发送，证明连接仍然存活
                    if (data instanceof HeartbeatDataPackage) {
                        String dn = ((HeartbeatDataPackage) data).getDn();
                        // 更新心跳时间 + 上报 ONLINE 状态
                        online(dn);
                        return;
                    }

                    // ========== 分支 C：Modbus 响应包（数据上报）==========
                    // 格式：标准 Modbus TCP 帧（>7 字节）
                    // 这是对 readTask 发送的读取命令的响应
                    if (data instanceof ResponseDataPackage) {
                        // 设备数据上报
                        String dn = client.getDn();
                        // 收到任何响应数据都视为设备活跃，更新心跳
                        online(dn);

                        // 提取 MBAP Header（7 字节帧头）
                        MbapHeader header = ((ResponseDataPackage) data).getHeader();
                        // 提取 Modbus PDU（协议数据单元）
                        ModbusPdu pdu = ((ResponseDataPackage) data).getPdu();

                        // 【关键】通过 Transaction ID 匹配之前发送的请求
                        // Transaction ID 在 MBAP Header 中，发送时写入，响应时原样返回
                        short transactionId = header.getTransactionId();

                        // 从事务映射表中取出对应的属性定义（并移除）
                        // 如果找不到，说明是过期/无效的响应，忽略
                        ModbusThingModel.Property property = client.getTransactionMap().remove(transactionId);
                        if (property == null) return;

                        // 【核心】根据 Function Code 分发处理不同的寄存器类型
                        switch (pdu.getFunctionCode()) {
                            case ReadCoils:
                                // 功能码 0x01：读线圈状态（位操作）
                                // 响应格式：[FunctionCode][ByteCount][CoilStatus...]
                                // 跳过前 2 字节（FunctionCode + ByteCount），取实际线圈数据
                                ByteBuf coilStatus = ((ReadCoilsResponse) pdu).getCoilStatus();
                                byte[] coilBytes = Arrays.copyOfRange(coilStatus.array(), 2, coilStatus.array().length);
                                // property.parse()：根据物模型定义的数据类型和字节序解析原始字节
                                Object coilValue = property.parse(coilBytes);
                                // 上报属性值到平台
                                report(PropertyReport.builder()
                                    .productKey(client.getProductKey())
                                        .deviceName(dn)
                                        .params(Dict.create().set(property.getIdentifier(), coilValue))
                                        .build());
                                break;

                            case ReadDiscreteInputs:
                                // 功能码 0x02：读离散输入状态（位操作，只读）
                                // 响应格式：[FunctionCode][ByteCount][InputStatus...]
                                ByteBuf inputStatus = ((ReadDiscreteInputsResponse) pdu).getInputStatus();
                                byte[] inputBytes = Arrays.copyOfRange(inputStatus.array(), 2, inputStatus.array().length);
                                Object inputValue = property.parse(inputBytes);
                                report(PropertyReport.builder()
                                        .productKey(client.getProductKey())
                                        .deviceName(dn)
                                        .params(Dict.create().set(property.getIdentifier(), inputValue))
                                        .build());
                                break;

                            case ReadHoldingRegisters:
                                // 功能码 0x03：读保持寄存器（16 位，最常用）
                                // 响应格式：[FunctionCode][ByteCount][RegisterHi][RegisterLo]...
                                // 每个寄存器占 2 字节
                                ByteBuf holdingRegisters = ((ReadHoldingRegistersResponse) pdu).getRegisters();
                                byte[] holdingRegistersBytes = Arrays.copyOfRange(holdingRegisters.array(), 2, holdingRegisters.array().length);
                                Object holdingRegistersValue = property.parse(holdingRegistersBytes);
                                report(PropertyReport.builder()
                                        .productKey(client.getProductKey())
                                        .deviceName(dn)
                                        .params(Dict.create().set(property.getIdentifier(), holdingRegistersValue))
                                        .build());
                                break;

                            case ReadInputRegisters:
                                // 功能码 0x04：读输入寄存器（16 位，只读，如传感器读数）
                                // 响应格式与保持寄存器相同
                                ByteBuf inputRegisters = ((ReadInputRegistersResponse) pdu).getRegisters();
                                byte[] inputRegistersBytes = Arrays.copyOfRange(inputRegisters.array(), 2, inputRegisters.array().length);
                                Object inputRegistersValue = property.parse(inputRegistersBytes);
                                report(PropertyReport.builder()
                                        .productKey(client.getProductKey())
                                        .deviceName(dn)
                                        .params(Dict.create().set(property.getIdentifier(), inputRegistersValue))
                                        .build());
                                break;

                            default:
                                // 不支持的功能码，忽略
                                return;
                        }
                    }

                    // ========== 安全机制：未注册设备断开连接 ==========
                    // 如果设备还没有发送注册包（不在 clientMap 中），关闭连接
                    // 防止未授权的设备长期占用 TCP 连接
                    if (!clientMap.containsKey(client.getDn())) {
                        socket.close();
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            };

            // 将 bufferConsumer 设置为 client 的数据消费者
            // 当 Socket 收到数据时，会自动回调 bufferConsumer
            client.setConsumer(bufferConsumer);
            log.debug("accept modbus client [{}] connection", socket.remoteAddress());
        } catch (Exception e) {
            e.printStackTrace();
            client.shutdown();
        }
    }

    /**
     * 【组件状态变更】- 配置热更新入口
     *
     * ============================================================================
     * 【调用时机】
     *  由 ThingComponent.config() 定时轮询触发（每 5 秒）
     *
     * 【处理逻辑】
     *  1. 设置双向引用（ModbusVerticle → ModbusComponent）
     *  2. enable=false：停止定时任务 → 断开所有连接 → 停止 TCP Server
     *  3. enable=true：解析 JSON 配置 → 先停旧再启新
     *     → 启动 TCP Server（监听 Modbus 端口）
     *     → 启动 readTask 定时轮询（间隔由 config.timer 决定）
     *     → 启动 offlineCheckTask 离线检测（每 40 秒）
     *
     * @param enable true=启用组件, false=停用组件
     * @param config JSON 配置：{"port":502, "timer":10}
     * @return true=操作成功
     */
    @Override
    public boolean stateChange(boolean enable, String config) {
        // 设置双向引用：ModbusVerticle 需要引用 ModbusComponent 来设置 connectHandler
        modbusVerticle.setModbusComponent(this);

        // ========== 停用组件 ==========
        if (!enable) {
            // ① 停止定时任务（readTask + offlineCheckTask）
            cancelTasks();
            // ② 关闭所有客户端 TCP 连接
            closeAllClients();
            // ③ 停止 TCP Server（释放端口）
            modbusVerticle.stopServer();
            return true;
        }

        // ========== 启用组件 ==========
        if (config == null) {
            return false; // 配置为空，无法启动
        }

        // 解析 JSON 配置 → ModbusConfig 对象
        // 配置格式：{"port":502, "timer":10}
        ModbusConfig modbusConfig = JsonUtils.parseObject(config, ModbusConfig.class);
        if (modbusConfig == null) {
            log.error("parse json modbus config failed.");
            return false;
        }
        this.modbusConfig = modbusConfig;

        // 【重要】先停止旧服务（如果正在运行），再启动新服务
        // 这确保了配置热更新时的平滑过渡
        cancelTasks();           // 停止旧的定时任务
        closeAllClients();       // 断开旧的客户端连接
        modbusVerticle.stopServer(); // 停止旧的 TCP Server

        // 启动新配置的 TCP Server（监听 Modbus 端口）
        modbusVerticle.startServer(modbusConfig);

        // 启动属性读取定时任务
        // 间隔由 config.timer 决定（如 10 秒）
        // scheduleAtFixedRate：固定频率执行，不受任务执行时间影响
        readTaskFuture = taskScheduler.scheduleAtFixedRate(
                this::readTask,
                Duration.ofSeconds(modbusConfig.getTimer())
        );

        // 启动离线检测定时任务
        // 固定每 40 秒执行一次
        // 40 秒的选择：心跳超时是 keepAliveTimeout * 2 = 120 秒
        // 每 40 秒检查一次可以在最多 2 次检查内发现离线设备
        offlineCheckTaskFuture = taskScheduler.scheduleAtFixedRate(
                this::offlineCheckTask,
                Duration.ofSeconds(40)
        );
        return true;
    }

    @Override
    protected void deviceOta(DeviceOta action) {
        // Modbus 协议下，OTA 升级暂不支持（空实现）
        // 如果需要支持 OTA，需要通过 Modbus 写寄存器命令实现
    }

    /**
     * 【取消所有定时任务】
     *
     * cancel(true) 参数表示：
     *  - 如果任务正在执行，尝试中断执行线程
     *  - 如果任务尚未执行，从调度队列中移除
     */
    private void cancelTasks() {
        if (readTaskFuture != null) {
            readTaskFuture.cancel(true);
            readTaskFuture = null;
        }
        if (offlineCheckTaskFuture != null) {
            offlineCheckTaskFuture.cancel(true);
            offlineCheckTaskFuture = null;
        }
    }

    /**
     * 【关闭所有客户端连接并清理缓存】
     *
     * 清理顺序：
     *  1. 关闭所有 TCP 连接（client.shutdown()）
     *  2. 清空 clientMap（设备→连接映射）
     *  3. 清空 heartbeatDevice（心跳记录）
     *  4. 清空 dnToDevice（设备信息缓存）
     *  5. 清空 pkToThingModel（物模型缓存）
     *
     * 注意：pkToThingModel 缓存的是物模型定义（不常变），
     * 清空后下次设备上线时会重新从数据库加载。
     */
    private void closeAllClients() {
        clientMap.values().forEach(VertxModbusClient::shutdown);
        clientMap.clear();
        heartbeatDevice.clear();
        dnToDevice.clear();
        pkToThingModel.clear();
    }

    /**
     * 【定时轮询读取设备属性】- Modbus 主机模式的核心
     *
     * ============================================================================
     * 【为什么需要定时轮询？】
     *  Modbus 协议是主从架构，只有 Master（本平台）可以发起读取请求，
     *  Slave（设备/网关）不会主动上报数据。
     *  因此需要定时遍历所有设备，逐个发送读取命令。
     *
     * 【轮询流程】
     *  1. 获取所有已连接的 client
     *  2. 对每个 client，用线程池异步执行：
     *     a. 查找该设备的物模型（产品属性定义）
     *     b. 遍历物模型中的所有属性
     *     c. 对每个属性，构造 RequestDataPackage：
     *        - transactionId：事务 ID（用于匹配响应）
     *        - slaveId：Modbus 从机地址
     *        - functionCode：功能码（1=Coils, 2=DiscreteInputs, 3=HoldingRegisters, 4=InputRegisters）
     *        - address：寄存器起始地址
     *        - quantity：寄存器数量
     *     d. 将 (transactionId → property) 映射存入 transactionMap
     *        （收到响应时通过 transactionId 找回属性定义来解析数据）
     *     e. DataEncoder.encode() 编码为 Modbus TCP 帧
     *     f. client.sendMessage() 发送
     *     g. sleep 2 秒（Modbus 设备不支持并发，避免数据混乱）
     *
     * 【响应处理】
     *  响应在 handle() 方法的 ResponseDataPackage 分支处理：
     *    → 根据 transactionId 从 transactionMap 取回 property
     *    → 根据 functionCode 解析不同寄存器类型的数据
     *    → property.parse() 将原始字节转为业务值
     *    → report(PropertyReport) 上报
     *
     * 【重要】Modbus 设备不支持并发读取！
     *  极短时间内发送多个读取命令，设备可能只响应第一个。
     *  因此在每个属性之间 sleep 2 秒。
     */
    private void readTask() {
        // ========== 步骤 1：获取所有已连接设备 ==========
        // 创建快照列表，防止遍历过程中 clientMap 被并发修改
        ArrayList<VertxModbusClient> clients = new ArrayList<>(clientMap.values());

        // ========== 步骤 2：遍历每个设备，异步发送读取命令 ==========
        for (VertxModbusClient client : clients) {
            // 查找设备信息
            DeviceInfo device = dnToDevice.get(client.getDn());
            if (device == null) continue; // 设备未注册，跳过

            // 【关键】使用线程池异步执行，避免阻塞定时任务调度线程
            // 每个设备的属性轮询在独立线程中执行
            ThreadUtil.execAsync(() -> {
                // 双重检查：设备可能在异步执行前已被移除
                if (!clientMap.containsKey(client.getDn())) return;
                try {
                    // 步骤 2a：获取/加载物模型
                    // computeIfAbsent：首次访问从 DB 加载，后续从缓存读取
                    ModbusThingModel thingModel = pkToThingModel.computeIfAbsent(
                            device.getProductKey(),
                            modbusThingModelApi::findByProductKey
                    );
                    if (thingModel == null) {
                        log.warn("thingModel not found for productKey: {}", device.getProductKey());
                        return;
                    }

                    // 步骤 2b：遍历物模型中的所有属性
                    // 每个属性对应一个 Modbus 寄存器读取命令
                    for (ModbusThingModel.Property property : thingModel.getModel().getProperties()) {
                        // 步骤 2c：构造 RequestDataPackage（读寄存器命令）
                        RequestDataPackage requestData = RequestDataPackage.builder()
                                .transactionId(client.getTransactionId())   // 事务 ID（1-999 循环）
                                .slaveId(client.getSlaveId())               // 从机地址（来自注册包）
                                .functionCode(Byte.parseByte(property.getRegType())) // 功能码（来自物模型）
                                .address(property.getRegAddr().shortValue()) // 寄存器起始地址（来自物模型）
                                .quantity(property.getRegNum().shortValue()) // 寄存器数量（来自物模型）
                                .build();

                        // 步骤 2d：【关键】建立 Transaction ID → Property 映射
                        // 当收到响应时，通过 Transaction ID 找到对应的 Property
                        // Property 包含了解析数据所需的所有信息（数据类型、字节序等）
                        client.getTransactionMap().put(requestData.getTransactionId(), property);

                        // 步骤 2e：编码为 Modbus TCP 帧并发送
                        Buffer buffer = DataEncoder.encode(requestData);
                        client.sendMessage(buffer);

                        // 步骤 2f：【重要】等待 2 秒，避免设备处理不过来
                        // Modbus 设备（特别是 RS485 总线上的设备）不支持并发读取
                        // 极短时间内发送多个命令，设备可能只响应第一个
                        // 2 秒是经验值，可根据实际设备调整
                        ThreadUtil.safeSleep(2000);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    @Override
    protected void serviceInvoke(ServiceInvoke action) {
        // Modbus 主从模式下，服务调用暂不支持（空实现）
        // 如果需要支持，可以通过 Modbus 写寄存器或自定义功能码实现
    }

    @Override
    protected void propertyGet(PropertyGet action) {
        // Modbus 主从模式下，属性获取通过 readTask 定时轮询实现（空实现）
        // 不需要额外的属性获取命令
    }

    @Override
    protected void propertySet(PropertySet action) {
        // Modbus 主从模式下，属性设置暂不支持（空实现）
        // 如需支持写寄存器：
        // 1. 从 action.params 获取要写入的值
        // 2. 查物模型获取寄存器地址
        // 3. 构造 WriteSingleRegister(0x06) 或 WriteMultipleRegisters(0x10) PDU
        // 4. DataEncoder.encode() → client.sendMessage() 发送
    }

    @Override
    protected void deviceConfig(DeviceConfig action) {
        // Modbus 主从模式下，设备配置下发暂不支持（空实现）
    }

    @Override
    protected void deviceTopoChange(DeviceTopoChange action) {
        // Modbus 场景下，拓扑变更暂不支持（空实现）
        // Modbus 设备的拓扑关系通过 RS485 总线物理连接决定，不需要平台通知
    }

    /**
     * 【离线检测定时任务】- 每 40 秒执行一次
     *
     * ============================================================================
     * 【两种离线判定机制】
     *
     *  机制 1：Socket 层超时检测（client.isOnline()）
     *    - 判断依据：Socket 最后活跃时间 > keepAliveTimeoutMs（默认 60 秒）
     *    - 动作：shutdown() 关闭客户端（Socket 层面的清理）
     *    - 场景：网络断开、网关断电、TCP 连接异常中断
     *
     *  机制 2：业务层心跳超时检测（heartbeatDevice 检查）
     *    - 判断依据：当前时间 - 最后心跳时间 > keepAliveTimeout * 2（默认 120 秒）
     *    - 动作：report(OFFLINE) 上报离线状态
     *    - 场景：网关 TCP 连接正常，但设备不发送心跳包（可能是网关故障）
     *
     *  为什么心跳超时是 keepAliveTimeout * 2？
     *    - 给网关 2 倍的心跳周期容错时间
     *    - 避免因网络抖动导致的误判离线
     *
     * ============================================================================
     * 【线程安全】
     *
     *  - 创建 clientMap 的快照列表，避免遍历时被并发修改
     *  - heartbeatDevice 使用 Iterator.remove() 安全删除
     */
    private void offlineCheckTask() {
        log.info("keepClientTask");

        // ========== 机制 1：Socket 层超时检测 ==========
        // 创建快照列表，防止并发时 clientMap 被修改导致 ConcurrentModificationException
        ArrayList<VertxModbusClient> clients = new ArrayList<>(clientMap.values());
        for (VertxModbusClient client : clients) {
            // isOnline()：检查 Socket 最后活跃时间是否超时
            if (!client.isOnline()) {
                // 超时 → 关闭 Socket 连接
                client.shutdown();
            }
        }

        // ========== 机制 2：业务层心跳超时检测 ==========
        // 遍历心跳记录，检查是否有设备心跳超时
        Iterator<Map.Entry<String, Long>> iterator = heartbeatDevice.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            String dn = entry.getKey();
            Long time = entry.getValue(); // 最后一次心跳时间戳

            // 当前时间 - 最后心跳时间 > keepAliveTimeout * 2（默认 120 秒）
            if (System.currentTimeMillis() - time > keepAliveTimeout * 2) {
                // 移除心跳记录（防止重复判定离线）
                iterator.remove();

                // 查找对应的客户端连接
                VertxModbusClient client = clientMap.get(dn);
                if (client != null) {
                    // 【上报离线状态】通知平台该设备已离线
                    // 消息会通过 ThingComponent.report() 发送到 device_thing topic
                    // → DeviceStateCheckHandler 消费 → 更新 Redis 缓存 + 数据库状态
                    report(DeviceStateChange.builder()
                            .productKey(client.getProductKey())
                            .deviceName(dn)
                            .state(DeviceState.OFFLINE)
                            .time(System.currentTimeMillis())
                            .build());
                }
            }
        }
    }

    /**
     * 【发送消息给指定设备】
     * 通过 dn 查找对应的 Modbus 客户端连接并发送数据。
     *
     * @param dn  设备名称
     * @param msg Modbus TCP 帧数据
     */
    public void sendMsg(String dn, Buffer msg) {
        VertxModbusClient modbusClient = clientMap.get(dn);
        if (modbusClient != null) {
            modbusClient.sendMessage(msg);
        }
    }

    /**
     * 【设备上线处理】
     * 记录心跳时间 → 上报 ONLINE 状态到平台
     *
     * @param dn 设备名称
     */
    public void online(String dn) {
        heartbeatDevice.put(dn, System.currentTimeMillis());
        VertxModbusClient client = clientMap.get(dn);
        if (client != null) {
            //上线
            report(DeviceStateChange.builder()
                    .deviceName(dn)
                    .productKey(client.getProductKey())
                    .state(DeviceState.ONLINE)
                    .build());
        }
    }


    @Override
    public String getType() {
        return "modbus-custom";
    }

    @Override
    public String getName() {
        return "有人云的USR-G770数传终端的modbus-tcp协议组件";
    }

}
