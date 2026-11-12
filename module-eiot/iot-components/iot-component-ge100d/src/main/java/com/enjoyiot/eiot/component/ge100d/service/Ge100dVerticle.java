package com.enjoyiot.eiot.component.ge100d.service;

import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.component.ge100d.constant.Ge100dConstants;
import com.enjoyiot.eiot.component.ge100d.model.Ge100dConfig;
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

/**
 * <h1>GE100D MQTT Verticle</h1>
 *
 * <h2>Topic 设计</h2>
 * <pre>
 * 上行: ge100d/{productKey}/{gatewayDn}/serial/up
 *   网关上报已解析的 JSON 数据: {"温度":25.5,"湿度":60.2}
 *
 * 下行: ge100d/{productKey}/{gatewayDn}/serial/down
 *   服务端下发控制命令: {"cmd":"asdfa"}
 * </pre>
 */
@Slf4j
@Component
@Data
public class Ge100dVerticle extends AbstractVerticle implements Runnable {

    private Ge100dComponent ge100dComponent;

    public void setGe100dComponent(Ge100dComponent c) { this.ge100dComponent = c; }

    private Ge100dConfig config;
    /** 连接状态：由 vert.x 回调线程写、定时重连/下发线程读，需 volatile 保证可见性 */
    private volatile boolean mqttConnected = false;
    private MqttClient client;
    private volatile Boolean taskStarted = Boolean.FALSE;
    private volatile Boolean taskStop = Boolean.FALSE;
    /** 防重入：connect() 是异步的，避免 3s 定时任务在握手未完成时重复发起连接 */
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private ScheduledThreadPoolExecutor connectTask = ThreadUtil.newScheduled(1, Ge100dConstants.CONNECT_THREAD_NAME);

    /**
     * 启动 GE100D 的 MQTT 客户端。
     *
     * <p>本方法完成 MQTT 客户端的初始化配置，但不会立即建立 TCP 连接。
     * 真正的连接动作由定时任务 {@link #connectTask} 在 {@link #run()} 中执行。
     * 将「创建」和「连接」分离，是为了支持断线自动重连。</p>
     *
     * @param config GE100D 组件配置（从数据库配置表或默认配置加载），包含 MQTT Broker 地址、端口、用户名、密码
     */
    public void startServer(Ge100dConfig config) {
        // ==== 第 1 步：保存配置引用，后续重连/下发时都会用到 ====
        this.config = config;

        // ==== 第 2 步：构建 MQTT 客户端（含连接选项） ====
        // 配置变更重启时由 buildClient() 重建；断线自动重连时 run() 中也会重建
        client = buildClient();

        // ==== 第 4 步：重置停止标记 ====
        // 如果之前调用过 stopServer()，taskStop 会被设为 true
        // 这里重置为 false，使得定时任务 run() 不再被拦截
        taskStop = false;

        // ==== 第 5 步：启动连接重试定时器（仅首次） ====
        // taskStarted 确保定时任务只创建一次，避免重复调用 startServer 时堆积多个定时器
        if (!taskStarted) {
            taskStarted = true;
            // scheduleWithFixedDelay:
            //   - 参数1 this: 当前对象实现了 Runnable，定时回调 run() 方法
            //   - 参数2 3: 首次延迟 3 秒后执行（给应用程序启动留出缓冲时间）
            //   - 参数3 3: 之后每次执行完等待 3 秒再执行下一次
            //   - 参数4 TimeUnit.SECONDS: 时间单位
            // 这个定时器的作用是断线自动重连：run() 中判断 mqttConnected 状态，
            // 如果掉了就重新 connect，每 3 秒重试一次
            connectTask.scheduleWithFixedDelay(this, Ge100dConstants.RECONNECT_DELAY_SECONDS,
                    Ge100dConstants.RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
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
                // ClientId：MQTT 协议要求每个连接有唯一的客户端标识
                // 格式: ge100d_server_ + 随机 UUID（如 "ge100d_server_a1b2c3d4e5f6"）
                // 使用 UUID 的好处：同一台服务器启动多个实例/多次重连不会冲突
                .setClientId(Ge100dConstants.CLIENT_ID_PREFIX + IdUtil.fastSimpleUUID())
                // 用户名：用于 EMQX Broker 的认证鉴权，需和 EMQX 侧配置一致
                .setUsername(config.getMqttUsername())
                // 密码：同上，配合用户名完成认证
                .setPassword(config.getMqttPassword())
                // CleanSession=true：每次连接都是全新会话
                // Broker 不会保留断线期间的离线消息，连接成功后也不会重推历史消息
                // 对下行控制场景来说，离线期间的指令本身就无意义，所以选 true
                .setCleanSession(true)
                // MaxInflightQueue=10000：允许同时处于"已发送但未确认"状态的最大消息数
                // 值越大吞吐量越高，但内存占用也越大；10000 对控制指令场景绰绰有余
                .setMaxInflightQueue(Ge100dConstants.MAX_INFLIGHT_QUEUE)
                // KeepAlive=60s：心跳间隔，60 秒内无收发则发送 PINGREQ 保活
                // Broker 在 1.5 个 KeepAlive（即 90s）无响应后判定客户端掉线
                .setKeepAliveInterval(Ge100dConstants.KEEP_ALIVE_SECONDS);
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

            // ge100d/{pk}/{dn}/serial/up
            Map<String, Integer> subscribes = new HashMap<>();
            subscribes.put(Ge100dConstants.TOPIC_UP, 1);

            c.connect(config.getMqttPort(), config.getMqttHost(), s -> {
                // connect 回调（成功或失败）都代表本轮连接已结束，释放防重入锁
                connecting.set(false);
                if (s.succeeded()) {
                    log.info("ge100d client connected to {}:{}", config.getMqttHost(), config.getMqttPort());
                    mqttConnected = true;
                    c.subscribe(subscribes, e -> {
                        if (e.succeeded()) {
                            log.info("ge100d subscribed: {}", e.result());
                        } else {
                            log.error("ge100d subscribe fail", e.cause());
                        }
                    });
                } else {
                    mqttConnected = false;
                    log.error("ge100d connect fail", s.cause());
                }
            })
            .publishHandler(msg -> {
                String topic = msg.topicName();
                // ge100d/{pk}/{dn}/serial/up
                String[] parts = topic.split("/");
                if (parts.length < Ge100dConstants.TOPIC_PART_COUNT) return;
                String pk = parts[1]; //从topic 中解析出 产品key
                String gwId = parts[2]; //从topic 中解析出 网关设备的dn

                // MQTT 回调运行在 Vert.x event-loop 线程上，但 handleSerialRead 内部会
                // 同步访问 Redis/DB（Spring Cache 物模型、设备缓存、规则引擎等），
                // 若直接在 event-loop 上执行，一旦缓存/数据库慢或无响应会阻塞整个
                // event-loop，导致该客户端的心跳、收发全部停摆（vertx blocked thread）。
                // 因此这里切换到 worker 线程执行；ordered=true 保证同一连接的消息按序处理。
                // 先在 event-loop 上同步取字符串，避免 Netty Buffer 在回调返回后被释放复用。
                String payload = msg.payload().toString();
                vertx.executeBlocking(promise -> {
                    try {
                        handleSerialRead(pk, gwId, payload);
                        promise.complete();
                    } catch (Throwable e) {
                        promise.fail(e);
                    }
                }, true).onFailure(e -> log.error("ge100d handle error: topic={}", topic, e));
            })
            .closeHandler(e -> {
                // 只响应当前 client 的事件；被重连替换掉的旧 client 的迟到回调直接忽略，
                // 避免旧连接断开误把新连接的状态置 false
                if (this.client != c) return;
                mqttConnected = false;
                log.info("ge100d client closed");
            })
            .exceptionHandler(e -> {
                if (this.client != c) return;
                log.error("ge100d client error", e);
            });
        } catch (Exception e) {
            connecting.set(false);
            log.error("ge100d start client failed", e);
        }
    }

    /**
     * 处理上行数据。
     * <ol>
     *   <li>轮询应答（zqnetret0qz / zqnetret1qz）→ 提取状态，转 Component 处理</li>
     *   <li>JSON 格式（硬件已升级固件）→ 解析键值对上报</li>
     *   <li>其他 → 记录日志忽略</li>
     * </ol>
     */
    private void handleSerialRead(String pk, String gwId, String payloadStr) {
        // 情形 1: 轮询应答 — 非 JSON，是固定格式字符串
        // zqnetret0qz → 继电器状态 0（关）
        // zqnetret1qz → 继电器状态 1（开）
        if (payloadStr != null && payloadStr.startsWith(Ge100dConstants.POLL_RESPONSE_PREFIX)) {
            handlePollResponse(pk, gwId, payloadStr);
            return;
        }

        // 情形 2: 尝试 JSON 解析
        try {
            JsonObject json = new JsonObject(payloadStr);
            Map<String, Object> data = new HashMap<>();
            for (String key : json.fieldNames()) {
                data.put(key, json.getValue(key));
            }
            if (!data.isEmpty()) {
                ge100dComponent.handleSerialRead(pk, gwId, data);
                return;
            }
        } catch (Exception e) {
            log.debug("ge100d non-json data ignored: gw={}, payload={}", gwId, payloadStr);
        }
    }

    /**
     * 处理轮询应答。
     * 硬件回复 zqnetretXqz，X 为继电器状态（0 或 1），
     * 交给 Component 映射到物模型 ge_ 属性并上报。
     */
    private void handlePollResponse(String pk, String gwId, String payload) {
        // zqnetret1qz → 状态=1，其余默认状态=0
        int state = Ge100dConstants.POLL_RESPONSE_ON.equals(payload) ? 1 : 0;
        ge100dComponent.handlePollResponse(pk, gwId, state);
    }

    /**
     * 【MQTT 下发控制命令 — 整个下行链路的物理出口】
     *
     * <p>将命令字符串通过 MQTT 发送给 GE100D 网关。这是软件到硬件的最后一步。</p>
     *
     * <h3>Topic 格式</h3>
     * <pre>
     * 下行: ge100d/{productKey}/{gatewayDn}/serial/down
     * 例:   ge100d/apFx3ja3p3nHiSRW/ge1/serial/down
     * </pre>
     *
     * <h3>Payload 格式</h3>
     * <pre>
     * {"cmd": "RELAY1_ON"}
     *   ↑       ↑
     *  固定key  命令字符串（由服务调用的 raw_ 参数传入）
     * </pre>
     *
     * <h3>QoS 说明</h3>
     * AT_LEAST_ONCE（QoS 1）：保证消息至少被 Broker 收到一次，适用于控制指令。
     *
     * @param pk   产品 Key（ProductKey），从设备信息获取
     * @param gwId 网关设备名称（DeviceName/dn），用于构建 MQTT Topic
     * @param cmd  命令字符串，由 Ge100dComponent 从 raw_ 参数中提取，原样透传给硬件
     *             例: "RELAY1_ON"、"AERATOR_OFF"、"abcde"
     */
    public void publishCommand(String pk, String gwId, String cmd) {
        // MQTT 客户端未初始化或连接已断开 → 无法下发
        if (client == null || !mqttConnected) {
            log.warn("ge100d mqtt not connected, skip send to gw={}", gwId);
            return;
        }
        // 构建下行 Topic: ge100d/{pk}/{dn}/serial/down
        String topic = Ge100dConstants.TOPIC_DOWN_PREFIX + pk + "/" + gwId + Ge100dConstants.TOPIC_DOWN_SUFFIX;
        // 构建 JSON payload: {"cmd": "RELAY1_ON"}
        String payload = cmd;
        // MQTT 发布：QoS 1（至少一次送达），不保留（retain=false），不重复（dup=false）
        client.publish(topic, Buffer.buffer(payload),
                MqttQoS.AT_LEAST_ONCE, false, false)
                // 发送成功：仅记录 debug 日志
                .onSuccess(h -> log.debug("ge100d down: topic={}, cmd={}", topic, cmd))
                // 发送失败：记录 error 日志（可能 Broker 断开、网络异常等）
                .onFailure(e -> log.error("ge100d down fail: topic={}", topic, e));
    }

    public void stopServer() {
        taskStop = true;
        try {
            log.info("ge100d stopping...");
            if (client != null) {
                client.disconnect()
                        .onSuccess(u -> { mqttConnected = false; log.info("ge100d disconnected"); })
                        .onFailure(u -> log.error("ge100d disconnect fail"));
            }
        } catch (Throwable e) {
            log.error("ge100d stop error", e);
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
