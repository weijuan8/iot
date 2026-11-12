package com.enjoyiot.eiot.component.geself.service;

import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.component.geself.constant.GeselfConstants;
import com.enjoyiot.eiot.component.geself.model.GeselfConfig;
import com.enjoyiot.framework.common.util.thread.ThreadUtil;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * <h1>GESELF MQTT Verticle</h1>
 *
 * <h2>Topic 设计</h2>
 * <pre>
 * 上行: geself/{productKey}/{deviceName}/{moduleId}/serial/up
 *   moduleId 用于设备自动注册（与 geio 组件一致）：硬件 publish 时携带该段，
 *   服务端据此校验模块存在 → 校验产品存在 → 自动 registerDevice → 自动 bindDevice
 *
 *   网关应答帧(JSON):     {"retmb":"0f050000ff008d14","status":"ok","sn":1}
 *   设备主动上报帧(JSON): {"tem":28.6,"PH":8.76,...} —— 无 retmb 字段
 *
 * 下行: geself/{productKey}/{deviceName}/{moduleId}/serial/down
 *   服务端下发帧(JSON):   {"mb":"0f050000ff00","sn":7,"ack":1,"crc":1}
 * </pre>
 *
 * <h2>应答帧与上报帧的判别</h2>
 * <pre>
 * 携带 retmb 或 status 键 → 下发指令的应答帧 → GeselfComponent.handleAck（按 sn 配对）
 * 其余一律视为设备主动上报 → GeselfComponent.handleSerialRead（整帧键值对上报，与 geio 一致）
 * </pre>
 */
@Slf4j
@Component
@Data
public class GeselfVerticle extends AbstractVerticle implements Runnable {

    private GeselfComponent geselfComponent;

    public void setGeselfComponent(GeselfComponent c) { this.geselfComponent = c; }

    private GeselfConfig config;
    /** 连接状态：由 vert.x 回调线程写、定时重连/下发线程读，需 volatile 保证可见性 */
    private volatile boolean mqttConnected = false;
    private MqttClient client;
    private volatile Boolean taskStarted = Boolean.FALSE;
    private volatile Boolean taskStop = Boolean.FALSE;
    /** 防重入：connect() 是异步的，避免 3s 定时任务在握手未完成时重复发起连接 */
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private ScheduledThreadPoolExecutor connectTask = ThreadUtil.newScheduled(1, GeselfConstants.CONNECT_THREAD_NAME);

    /**
     * 启动 GESELF 的 MQTT 客户端。
     *
     * <p>本方法完成 MQTT 客户端初始化配置，但不会立即建立 TCP 连接。
     * 真正的连接动作由定时任务 {@link #connectTask} 在 {@link #run()} 中执行，
     * 将「创建」和「连接」分离是为了支持断线自动重连。</p>
     *
     * @param config GESELF 组件配置，包含 MQTT Broker 地址、端口、用户名、密码
     */
    public void startServer(GeselfConfig config) {
        this.config = config;

        // 配置变更重启时由 buildClient() 重建；断线自动重连时 run() 中也会重建
        client = buildClient();

        taskStop = false;

        if (!taskStarted) {
            taskStarted = true;
            // 断线自动重连：每 3 秒重试一次
            connectTask.scheduleWithFixedDelay(this, GeselfConstants.RECONNECT_DELAY_SECONDS,
                    GeselfConstants.RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
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
                .setClientId(GeselfConstants.CLIENT_ID_PREFIX + IdUtil.fastSimpleUUID())
                .setUsername(config.getMqttUsername())
                .setPassword(config.getMqttPassword())
                .setCleanSession(true)
                .setMaxInflightQueue(GeselfConstants.MAX_INFLIGHT_QUEUE)
                .setKeepAliveInterval(GeselfConstants.KEEP_ALIVE_SECONDS);
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
            // TOPIC_UP 按环境取值：测试环境 testgeself/+/+/+/serial/up，生产环境 geself/+/+/+/serial/up（6 段）
            subscribes.put(GeselfConstants.TOPIC_UP, 1);

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
                            log.error("============= 【GESELF-错误】MQTT主题订阅失败 =============", e.cause());
                        }
                    });
                } else {
                    mqttConnected = false;
                    log.error("============= 【GESELF-错误】MQTT连接失败 =============", s.cause());
                }
            })
            .publishHandler(msg -> {
                String topic = msg.topicName();
                // geself/{pk}/{dn}/{moduleId}/serial/up
                String[] parts = topic.split("/");
                if (parts.length < GeselfConstants.TOPIC_PART_COUNT) return;
                String pk = parts[1];         // 产品 key
                String dn = parts[2];         // 设备 dn
                String moduleId = parts[3];   // 模块 ID（用于自动注册）

                // MQTT 回调运行在 Vert.x event-loop 线程上，但下行处理会访问 Redis/DB，
                // 直接在 event-loop 执行会阻塞整个 event-loop（vertx blocked thread）。
                // 因此切换到 worker 线程执行；ordered=true 保证同一连接的消息按序处理。
                // 先在 event-loop 上同步取字符串，避免 Netty Buffer 在回调返回后被释放复用。
                String payload = msg.payload().toString();
                vertx.executeBlocking(promise -> {
                    try {
                        handleSerialRead(pk, dn, moduleId, payload);
                        promise.complete();
                    } catch (Throwable e) {
                        promise.fail(e);
                    }
                }, true).onFailure(e -> log.error("============= 【GESELF-错误】处理上行数据异常 ============= 主题={}", topic, e));
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
                log.error("============= 【GESELF-错误】MQTT客户端异常 =============", e);
            });
        } catch (Exception e) {
            connecting.set(false);
            log.error("============= 【GESELF-错误】启动MQTT客户端失败 =============", e);
        }
    }

    /**
     * 处理上行数据（分派入口）。
     *
     * <p>上行消息均为 JSON，按 payload 中是否携带 {@code retmb} / {@code status} 字段区分两类：</p>
     *
     * <ol>
     *   <li>网关应答帧 {@code {"retmb":"0f050000ff008d14","status":"ok","sn":1}}
     *     → 对应某条下行控制指令的结果，按 sn 与 pending 指令配对后更新该路开关状态。</li>
     *   <li>设备主动上报帧（无应答字段），如
     *     {@code {"tem":28.6,"PH":8.76}}
     *     → 整帧展开为键值对后全量上报（与 geio 上报逻辑一致）。</li>
     * </ol>
     *
     * <p>JSON 解析失败时只做一层兼容修复：网关 null 值上报成裸 {@code ]} 的非法片段
     * （形如 {@code "pv_voltage":]}）按 0 处理再解析；仍失败则直接打印错误日志（含原始
     * payload）后忽略。</p>
     */
    private void handleSerialRead(String pk, String dn, String moduleId, String payloadStr) {
        if (payloadStr == null || payloadStr.isEmpty()) return;

        JsonObject json;
        try {
            json = new JsonObject(payloadStr);
        } catch (Exception e) {
            // 兼容修复（仅一层）：部分网关固件字段值为 null 时会上报非法 JSON，例如
            // {"id":"...","tem":25.6150,"pv_voltage":],"battery_voltage":]} —— 值被序列化成裸 `]`
            // （既不是合法值也不是空数组），导致整帧解析失败。这里将形如 "key":] 的片段
            // 规整为 "key":0（null 按 0 处理）后重新解析；仍失败则打印错误信息并忽略。
            try {
                json = new JsonObject(NULL_AS_BARE_BRACKET.matcher(payloadStr).replaceAll(":0"));
            } catch (Exception ex) {
                log.error("============= 【GESELF-错误】上行报文解析失败，已丢弃该条数据 ============= 设备dn={}, 原始报文={}", dn, payloadStr, ex);
                return;
            }
        }

        if (json.containsKey(GeselfConstants.FIELD_RETMB) || json.containsKey(GeselfConstants.FIELD_STATUS)) {
            // 应答帧：按 sn 与 pending 指令配对，确认控制结果
            handleAckFrame(pk, dn, moduleId, json);
        } else {
            // 其它 JSON 一律视为设备主动上报帧，整帧键值对上报（与 geio 上报逻辑一致）
            handleReportFrame(pk, dn, moduleId, json);
        }
    }

    /**
     * 网关 null 值上报成裸 {@code ]} 的异常片段。
     *
     * <p>匹配 {@code "pv_voltage":]} 这类「冒号后直接跟右括号」的非法值。
     * 合法 JSON 中冒号后只可能是值起始符（{@code "} / {@code [} / 数字 / true 等），
     * 永远不会是裸 {@code ]}，因此不会误伤合法数组。</p>
     */
    private static final Pattern NULL_AS_BARE_BRACKET = Pattern.compile(":\\s*]");

    /**
     * 处理网关应答帧：{"retmb":"0f050000ff008d14","status":"ok","sn":1}。
     *
     * <p>取出三个关键字段交给 {@link GeselfComponent#handleAck} 做 pending 配对：
     * retmb 仅用于日志记录（本组件不校验其 CRC），status 判定成败，sn 定位指令。</p>
     */
    private void handleAckFrame(String pk, String dn, String moduleId, JsonObject json) {
        String retmb = json.getString(GeselfConstants.FIELD_RETMB);
        String status = json.getString(GeselfConstants.FIELD_STATUS);
        Integer sn = json.getInteger(GeselfConstants.FIELD_SN);
        if (sn == null) {
            log.warn("【警告】应答帧缺少 sn 字段，已忽略：设备dn={}, 状态={}, 应答帧={}", dn, status, retmb);
            return;
        }
        geselfComponent.handleAck(pk, dn, moduleId, retmb, status, sn);
    }

    /**
     * 处理设备主动上报 JSON 帧（兼容 geio 上报方式）。
     *
     * <p>整帧所有顶层键值（如 tem、PH、DO 等）一并上报，
     * 由上层规则引擎按物模型匹配落库；本组件不做属性过滤。</p>
     */
    private void handleReportFrame(String pk, String dn, String moduleId, JsonObject json) {
        Map<String, Object> data = new HashMap<>(json.size());
        for (String key : json.fieldNames()) {
            data.put(key, json.getValue(key));
        }
        if (data.isEmpty()) {
            log.debug("【调试】上报帧为空JSON，已忽略：设备dn={}", dn);
            return;
        }
        geselfComponent.handleSerialRead(pk, dn, moduleId, data);
    }

    /**
     * 【MQTT 下发控制命令 — 整个下行链路的物理出口】
     *
     * <h3>Topic 格式</h3>
     * <pre>
     * 下行: geself/{productKey}/{deviceName}/{moduleId}/serial/down
     * 例:   geself/apFx3ja3p3nHiSRW/ge1/M001/serial/down
     * </pre>
     *
     * <h3>Payload 格式</h3>
     * <pre>
     * {"mb":"0f050000ff00","sn":7,"ack":1,"crc":1}
     * mb 为不含 CRC 的 MB 帧（CRC 由网关按 crc=1 自动计算），sn 由 GeselfComponent 递增
     * </pre>
     *
     * @param pk        产品 Key（ProductKey），从设备信息获取
     * @param dn        设备名称（DeviceName），用于构建 MQTT Topic
     * @param moduleId  模块 ID（用于拼下行 topic；若为 null 表示非自动注册设备）
     * @param cmd       下发帧字符串，由 GeselfComponent 拼接，原样下发
     */
    public void publishCommand(String pk, String dn, String moduleId, String cmd) {
        if (client == null || !mqttConnected) {
            log.warn("【警告】MQTT未连接，跳过下发：设备dn={}", dn);
            return;
        }
        if (moduleId == null || moduleId.isEmpty()) {
            // moduleId 由设备上行 topic 携带 + 组件内存缓存；缓存为空说明该设备从未上报过，
            // 拼不出正确下行 topic → 直接报错，便于排查（结构必须符合规则）
            log.error("============= 【GESELF-错误】下发取消，模块ID未知（设备从未上报过数据） ============= 产品pk={}, 设备dn={}", pk, dn);
            return;
        }
        String topic = GeselfConstants.TOPIC_DOWN_PREFIX + pk + "/" + dn + "/" + moduleId + GeselfConstants.TOPIC_DOWN_SUFFIX;
        client.publish(topic, Buffer.buffer(cmd),
                MqttQoS.AT_LEAST_ONCE, false, false)
                .onSuccess(h -> log.info("【信息】指令下发成功：主题={}, 指令={}", topic, cmd))
                .onFailure(e -> log.error("============= 【GESELF-错误】指令下发失败 ============= 主题={}, 指令={}", topic, cmd, e));
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
            log.info("【信息】正在停止GESELF组件...");
            if (client != null) {
                client.disconnect()
                        .onSuccess(u -> { mqttConnected = false; log.info("【信息】MQTT已断开连接"); })
                        .onFailure(u -> log.error("============= 【GESELF-错误】MQTT断开连接失败 ============="));
            }
        } catch (Throwable e) {
            log.error("============= 【GESELF-错误】停止组件异常 =============", e);
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
