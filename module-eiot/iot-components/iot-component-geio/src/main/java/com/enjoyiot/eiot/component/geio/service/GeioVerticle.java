package com.enjoyiot.eiot.component.geio.service;

import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.component.geio.constant.GeioConstants;
import com.enjoyiot.eiot.component.geio.model.GeioConfig;
import com.enjoyiot.framework.common.util.thread.ThreadUtil;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * <h1>GEIO MQTT Verticle</h1>
 *
 * <h2>Topic 设计</h2>
 * <pre>
 * 上行: geio/{productKey}/{gatewayDn}/{moduleId}/serial/up
 *   moduleId 用于 GEIO 设备自动注册：硬件 publish topic 时携带该段，
 *   服务端据此校验模块存在 → 校验产品存在 → 自动 registerDevice → 自动 bindDevice
 *
 *   硬件应答帧(JSON):   {"addr":设备地址,"cmd":"ret","y":[16 路继电器状态]}
 *   硬件主动上报帧(JSON): {"id":"...","time":"...","DO":4.48,"tem":28.6,"PH":8.76}
 *      无 cmd 字段 → 整帧键值对上报（与 ge100d 透传方式一致），无继电器语义
 *
 * 下行: geio/{productKey}/{gatewayDn}/{moduleId}/serial/down
 *   服务端下发控制帧(JSON): {"addr":设备地址,"cmd":"set","y":[≤16 位, 1=开 0=关 null=不控制]}
 *   服务端下发轮询帧(JSON): {"addr":设备地址,"cmd":"get","data":["y"]}
 * </pre>
 *
 * <h2>设备地址</h2>
 * <pre>
 * dn 最后一段数字即该设备的地址：geio_mlxy_test_01_01 → addr=1
 *   dn 其余段（区域/用户等）只是平台命名标识，不参与协议
 *   下行 addr 由 dn 末段解析；上行按 dn 末段解析结果做地址校验
 *   继电器位置由物模型参数标识符末段决定（geio_01~geio_16），与 dn 无关
 *   dn 不以 _数字 结尾 → 直接报错并丢弃，无默认地址兜底
 * </pre>
 */
@Slf4j
@Component
@Data
public class GeioVerticle extends AbstractVerticle implements Runnable {

    private GeioComponent geioComponent;

    public void setGeioComponent(GeioComponent c) { this.geioComponent = c; }

    private GeioConfig config;
    /** 连接状态：由 vert.x 回调线程写、定时重连/下发线程读，需 volatile 保证可见性 */
    private volatile boolean mqttConnected = false;
    private MqttClient client;
    private volatile Boolean taskStarted = Boolean.FALSE;
    private volatile Boolean taskStop = Boolean.FALSE;
    /** 防重入：connect() 是异步的，避免 3s 定时任务在握手未完成时重复发起连接 */
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private ScheduledThreadPoolExecutor connectTask = ThreadUtil.newScheduled(1, GeioConstants.CONNECT_THREAD_NAME);

    /**
     * 启动 GEIO 的 MQTT 客户端。
     *
     * <p>本方法完成 MQTT 客户端的初始化配置，但不会立即建立 TCP 连接。
     * 真正的连接动作由定时任务 {@link #connectTask} 在 {@link #run()} 中执行，
     * 将「创建」和「连接」分离是为了支持断线自动重连。</p>
     *
     * @param config GEIO 组件配置，包含 MQTT Broker 地址、端口、用户名、密码
     */
    public void startServer(GeioConfig config) {
        this.config = config;

        // 配置变更重启时由 buildClient() 重建；断线自动重连时 run() 中也会重建
        client = buildClient();

        taskStop = false;

        if (!taskStarted) {
            taskStarted = true;
            // 断线自动重连：每 3 秒重试一次
            connectTask.scheduleWithFixedDelay(this, GeioConstants.RECONNECT_DELAY_SECONDS,
                    GeioConstants.RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * 构建 MQTT 客户端（每次连接/重连都重建一个全新实例）。
     *
     * <p>vert.x 的 {@link MqttClient} 一旦建立过连接，断线后其内部连接状态不会干净复位，
     * 在同一实例上再次调用 connect() 会被 doConnect 拒绝
     * （IllegalStateException: Client connected），导致定时重连死循环刷错误日志。
     * 因此「创建」必须放到每次 connect 之前执行，而不是只建一次。</p>
     *
     * @return 全新 MqttClient 实例
     */
    private MqttClient buildClient() {
        MqttClientOptions options = new MqttClientOptions()
                .setClientId(GeioConstants.CLIENT_ID_PREFIX + IdUtil.fastSimpleUUID())
                .setUsername(config.getMqttUsername())
                .setPassword(config.getMqttPassword())
                .setCleanSession(true)
                .setMaxInflightQueue(GeioConstants.MAX_INFLIGHT_QUEUE)
                .setKeepAliveInterval(GeioConstants.KEEP_ALIVE_SECONDS);
        return MqttClient.create(vertx, options);
    }

    @Override
    public void run() {
        if (taskStop || config == null) return;
        if (mqttConnected) return;
        // 防重入：connect() 是异步的，上一次握手还没出结果时跳过本轮，避免重复发起连接
        if (!connecting.compareAndSet(false, true)) return;

        try {
            // 关键修复：每次重连前都重建 client。
            // vert.x 的 MqttClient 一旦连接过，断线后内部 connected 状态不会干净复位，
            // 同一实例再次 connect() 会被 doConnect 直接拒绝（IllegalStateException: Client connected），
            // 导致每 3 秒的重连死循环刷错。buildClient() 生成全新实例可绕开该限制。
            MqttClient c = buildClient();
            this.client = c;

            Map<String, Integer> subscribes = new HashMap<>();
            // TOPIC_UP 按环境取值：测试环境 testgeio/+/+/+/serial/up，生产环境 geio/+/+/+/serial/up（6 段）
            subscribes.put(GeioConstants.TOPIC_UP, 1);

            c.connect(config.getMqttPort(), config.getMqttHost(), s -> {
                // connect 回调（成功或失败）都代表本轮连接已结束，释放防重入锁
                connecting.set(false);
                if (s.succeeded()) {
                    log.info("【信息】MQTT客户端已连接到 {}:{}", config.getMqttHost(), config.getMqttPort());
                    mqttConnected = true;
                    c.subscribe(subscribes, e -> {
                        if (e.succeeded()) {
                            log.info("【信息】MQTT主题订阅成功：{}", e.result());
                        } else {
                            log.error("============= 【GEIO-错误】MQTT主题订阅失败 =============", e.cause());
                        }
                    });
                } else {
                    mqttConnected = false;
                    log.error("============= 【GEIO-错误】MQTT连接失败 =============", s.cause());
                }
            })
            .publishHandler(msg -> {
                String topic = msg.topicName();
                // geio/{pk}/{dn}/{moduleId}/serial/up
                String[] parts = topic.split("/");
                if (parts.length < GeioConstants.TOPIC_PART_COUNT) return;
                String pk = parts[1]; // 从 topic 中解析出 产品key
                String gwId = parts[2]; // 从 topic 中解析出 网关设备的dn
                String moduleId = parts[3]; // 从 topic 中解析出 模块 ID（用于自动注册）

                // MQTT 回调运行在 Vert.x event-loop 线程上，但 handleSerialRead 内部会
                // 同步访问 Redis/DB（设备缓存、物模型、规则引擎等），
                // 若直接在 event-loop 上执行，一旦缓存/数据库慢或无响应会阻塞整个
                // event-loop，导致该客户端的心跳、收发全部停摆（vertx blocked thread）。
                // 因此这里切换到 worker 线程执行；ordered=true 保证同一连接的消息按序处理。
                // 先在 event-loop 上同步取字符串，避免 Netty Buffer 在回调返回后被释放复用。
                String payload = msg.payload().toString();
                vertx.executeBlocking(promise -> {
                    try {
                        handleSerialRead(pk, gwId, moduleId, payload);
                        promise.complete();
                    } catch (Throwable e) {
                        promise.fail(e);
                    }
                }, true).onFailure(e -> log.error("============= 【GEIO-错误】处理上行数据异常 ============= 主题={}", topic, e));
            })
            .closeHandler(e -> {
                // 只响应当前 client 的事件；被重连替换掉的旧 client 的迟到回调直接忽略，
                // 避免旧连接断开误把新连接的状态置 false
                if (this.client != c) return;
                mqttConnected = false;
                log.info("【信息】MQTT客户端连接已关闭");
            })
            .exceptionHandler(e -> {
                if (this.client != c) return;
                log.error("============= 【GEIO-错误】MQTT客户端异常 =============", e);
            });
        } catch (Exception e) {
            connecting.set(false);
            log.error("============= 【GEIO-错误】启动MQTT客户端失败 =============", e);
        }
    }

    /**
     * 处理上行数据（分派入口）。
     *
     * <p>上行消息均为 JSON，按 payload 中是否携带 {@code cmd} 字段区分两类：</p>
     *
     * <ol>
     *   <li>应答帧 {@code {"addr":设备地址,"cmd":"ret","y":[1,1,0,...]}}
     *     → y 固定 16 位，按位表示第 1~16 路继电器状态，按位序更新物模型 geio_ 开关；
     *     addr 需与该设备名解析出的地址一致，防止同网段其它设备串扰。</li>
     *   <li>主动上报帧（无 cmd 字段），如
     *     {@code {"id":"...","time":"...","DO":4.48,"tem":28.6,"PH":8.76}}
     *     → 不涉及继电器，整帧展开为键值对后全量上报（与 ge100d 透传上报逻辑一致）。</li>
     * </ol>
     *
     * <p>JSON 解析失败时只做一层兼容修复：网关 null 值上报成裸 {@code ]} 的非法片段
     * （形如 {@code "pv_voltage":]}）按 0 处理再解析；仍失败则直接打印错误日志（含原始
     * payload）后忽略，不再做二次修复。</p>
     */
    private void handleSerialRead(String pk, String gwId, String moduleId, String payloadStr) {
        if (payloadStr == null || payloadStr.isEmpty()) return;

        JsonObject json;
        try {
            json = new JsonObject(payloadStr);
        } catch (Exception e) {
            // 兼容修复（仅一层）：部分网关固件字段值为 null 时会上报非法 JSON，例如
            // {"id":"...","time":"...","DO":10.1449,"tem":25.6150,"PH":9.1100,
            //  "pv_voltage":],"battery_voltage":],"charge_current":]} —— 值被序列化成裸 `]`
            // （既不是合法值也不是空数组），导致整帧解析失败，本可正常落库的 DO/tem/PH
            // 等字段一并丢失。这里将形如 "key":] 的片段规整为 "key":0（null 按 0 处理）
            // 后重新解析，避免丢整帧；仍失败则打印错误信息并忽略，不再二次修复。
            try {
                json = new JsonObject(NULL_AS_BARE_BRACKET.matcher(payloadStr).replaceAll(":0"));
            } catch (Exception ex) {
                log.error("============= 【GEIO-错误】上行报文解析失败，已丢弃该条数据 ============= 设备={}, 原始报文={}", gwId, payloadStr, ex);
                return;
            }
        }

        if (GeioConstants.CMD_RET.equals(json.getString("cmd"))) {
            // 应答帧：cmd == "ret"，按继电器位序更新开关状态
            handleRetFrame(pk, gwId, moduleId, json, payloadStr);
        } else {
            // 其它 JSON 一律视为设备主动上报帧（无 cmd 或未知 cmd），整帧键值对上报
            handleReportFrame(pk, gwId, moduleId, json);
        }
    }

    /**
     * 网关 null 值上报成裸 {@code ]} 的异常片段。
     *
     * <p>匹配 {@code "pv_voltage":]} 这类「冒号后直接跟右括号」的非法值。
     * 合法 JSON 中冒号后只可能是值起始符（{@code "} / {@code [} / 数字 / true 等），
     * 永远不会是裸 {@code ]}，因此不会误伤 {@code "y":[1,0,...]} 等合法数组。</p>
     */
    private static final Pattern NULL_AS_BARE_BRACKET = Pattern.compile(":\\s*]");

    /**
     * 处理 ret 应答帧：{"addr":设备地址,"cmd":"ret","y":[16 路当前状态]}。
     *
     * <p>全量 16 路状态，收到后按位序更新该产品物模型上所有 geio_ 开关。
     * addr 需与该设备名解析出的地址一致，防止同网段其它设备串扰。</p>
     */
    private void handleRetFrame(String pk, String gwId, String moduleId, JsonObject json, String payloadStr) {
        // 地址校验：上行帧 addr 需与该设备名解析出的地址一致（geio_xxx_01 → 1），
        // 不一致直接丢弃，防止同网段其它设备串扰
        Integer addr = json.getInteger("addr");
        int expectAddr;
        try {
            expectAddr = GeioConstants.resolveDeviceAddress(gwId);
        } catch (IllegalArgumentException e) {
            // 设备名不符合命名规则，无法确定该设备地址，直接报错并丢弃
            log.error("============= 【GEIO-错误】应答帧已丢弃，无法解析设备地址 ============= 设备dn={}, 原始报文={}",
                    gwId, payloadStr);
            return;
        }
        if (addr != null && addr != expectAddr) {
            log.warn("【警告】应答帧设备地址不匹配：设备dn={}, 期望地址={}, 实际地址={}, 原始报文={}",
                    gwId, expectAddr, addr, payloadStr);
            return;
        }

        JsonArray yArr = json.getJsonArray("y");
        if (yArr == null || yArr.isEmpty()) {
            log.warn("【警告】应答帧缺少 y 数组：设备={}, 原始报文={}", gwId, payloadStr);
            return;
        }

        // 解析 y 数组为按位状态列表（元素允许为 null，代表该路无效/占位）
        List<Integer> states = new ArrayList<>(yArr.size());
        for (Object item : yArr) {
            if (item == null) {
                states.add(null);
            } else if (item instanceof Number) {
                states.add(((Number) item).intValue());
            } else {
                log.warn("【警告】应答帧 y 数组元素非法：设备={}, 元素={}", gwId, item);
                return;
            }
        }
        geioComponent.handleRet(pk, gwId, moduleId, states);
    }

    /**
     * 处理设备主动上报 JSON 帧（无 cmd 字段，兼容 ge100d 上报方式）。
     *
     * <p>整帧所有顶层键值（含 id/time 及 DO、tem、PH 等环境/水质字段）一并上报，
     * 由上层规则引擎按物模型匹配落库；本组件不做属性过滤。</p>
     */
    private void handleReportFrame(String pk, String gwId, String moduleId, JsonObject json) {
        Map<String, Object> data = new HashMap<>(json.size());
        for (String key : json.fieldNames()) {
            data.put(key, json.getValue(key));
        }
        if (data.isEmpty()) {
            log.debug("【调试】上报帧为空JSON，已忽略：设备={}", gwId);
            return;
        }
        geioComponent.handleSerialRead(pk, gwId, moduleId, data);
    }

/**
     * 【MQTT 下发控制命令 — 整个下行链路的物理出口】
     *
     * <p>将控制帧字符串通过 MQTT 发送给 GEIO 设备。</p>
     *
     * <h3>Topic 格式</h3>
     * <pre>
     * 下行: geio/{productKey}/{gatewayDn}/{moduleId}/serial/down
     * 例:   geio/apFx3ja3p3nHiSRW/ge1/M001/serial/down
     * </pre>
     *
     * <h3>Payload 格式</h3>
     * <pre>
     * 控制: {"addr":设备地址,"cmd":"set","y":[1,null,0,...]} （≤16 位，null=不控制，尾部 null 裁剪）
     * 轮询: {"addr":设备地址,"cmd":"get","data":["y"]}
     * addr 由 GeioComponent 从设备名最后一段数字解析（如 geio_mlxy_test_01_01 → 1）
     * </pre>
     *
     * @param pk         产品 Key（ProductKey），从设备信息获取
     * @param gwId       网关设备名称（DeviceName/dn），用于构建 MQTT Topic
     * @param moduleId 模块 ID（用于拼下行 topic；若为 null 表示非自动注册设备）
     * @param cmd        控制帧字符串，由 GeioComponent 拼接，原样下发
     */
    public void publishCommand(String pk, String gwId, String moduleId, String cmd) {
        if (client == null || !mqttConnected) {
            log.warn("【警告】MQTT未连接，跳过下发：设备={}", gwId);
            return;
        }
        if (moduleId == null || moduleId.isEmpty()) {
            // moduleId 由设备上行 topic 携带 + 组件内存缓存；缓存为空说明该设备从未上报过，
            // 拼不出正确下行 topic → 直接报错，不下占位包，便于排查（结构必须符合规则）
            log.error("============= 【GEIO-错误】下发取消，模块ID未知（设备从未上报过数据） ============= 产品pk={}, 设备dn={}",
                    pk, gwId);
            return;
        }
        String topic = GeioConstants.TOPIC_DOWN_PREFIX + pk + "/" + gwId + "/" + moduleId + GeioConstants.TOPIC_DOWN_SUFFIX;
        client.publish(topic, Buffer.buffer(cmd),
                MqttQoS.AT_LEAST_ONCE, false, false)
                .onSuccess(h -> log.debug("【调试】指令下发成功：主题={}, 指令={}", topic, cmd))
                .onFailure(e -> log.error("============= 【GEIO-错误】指令下发失败 ============= 主题={}", topic, e));
    }

    /**
     * MQTT 连接状态。
     *
     * @return true 表示已连接，可下发命令
     */
    public boolean isMqttConnected() {
        return client != null && mqttConnected;
    }

    public void stopServer() {
        taskStop = true;
        try {
            log.info("【信息】正在停止GEIO组件...");
            if (client != null) {
                client.disconnect()
                        .onSuccess(u -> { mqttConnected = false; log.info("【信息】MQTT已断开连接"); })
                        .onFailure(u -> log.error("============= 【GEIO-错误】MQTT断开连接失败 ============="));
            }
        } catch (Throwable e) {
            log.error("============= 【GEIO-错误】停止组件异常 =============", e);
        }
    }

    @Override
    public void stop() {
        stopServer();
        if (connectTask != null && !connectTask.isShutdown()) {
            connectTask.shutdown();
        }
    }
}
