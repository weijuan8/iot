/*
 *
 *  * | Licensed 未经许可不能去掉「EnjoyIot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2024] [EnjoyIot]
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */
package com.enjoyiot.eiot.component.emqx.service;


import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.common.enums.DeviceState;
import com.enjoyiot.eiot.component.core.model.up.DeviceStateChange;
import com.enjoyiot.eiot.component.core.model.up.EventReport;
import com.enjoyiot.eiot.component.core.model.up.PropertyReport;
import com.enjoyiot.eiot.component.core.model.up.ServiceReply;
import com.enjoyiot.eiot.component.emqx.model.MqttConfig;
import com.enjoyiot.eiot.component.emqx.model.ThingDevice;
import com.enjoyiot.framework.common.util.collection.CollectionUtils;
import com.enjoyiot.framework.common.util.thread.ThreadUtil;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.RegisterDevice;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * EMQX 核心 Verticle - MQTT 连接管理与消息处理中心
 *
 * 【角色定位】
 * 本类是整个 EMQX 模块的中枢，承担以下职责：
 * 1. 管理 MQTT 客户端与 EMQX Broker 的连接
 * 2. 部署并管理 AuthVerticle（认证授权 HTTP 服务）
 * 3. 处理设备上行消息（属性上报、事件上报、服务回复等）
 * 4. 管理设备上下线状态
 * 5. 为 EmqxComponent 提供 MQTT 客户端，支持下发指令
 *
 * 【设计要点 - 为什么实现 Runnable？】
 * 本类实现了 Runnable 接口，将"连接管理逻辑"放在定时任务中执行。
 * 在 run() 方法中实现了两个阶段的初始化：
 *   阶段1：先确保 AuthVerticle（认证服务）启动成功
 *   阶段2：再建立 MQTT 客户端连接并订阅 Topic
 * 这样设计的好处：
 * - AuthVerticle 必须在 MQTT 连接之前启动，否则设备认证会失败
 * - 通过定时重试机制，在网络异常或 EMQX 未就绪时自动恢复连接
 *
 * 【设备认证与消息流的整体架构】
 *
 *  设备端                  EMQX Broker          本服务(EmqxVerticle)        平台后端
 *  ──────                 ──────────           ──────────────────        ────────
 *    │                       │                      │                       │
 *    │ ① MQTT CONNECT        │                      │                       │
 *    │──────────────────────▶│                      │                       │
 *    │                       │ ② HTTP 认证回调       │                       │
 *    │                       │─────────────────────▶│ AuthVerticle           │
 *    │                       │                      │   └→ DeviceApi.auth()  │
 *    │                       │◀─────────────────────│   └→ 认证结果返回       │
 *    │◀──────────────────────│                      │                       │
 *    │ ③ CONNACK             │                      │                       │
 *    │                       │                      │                       │
 *    │ ④ PUBLISH(属性上报)    │                      │                       │
 *    │──────────────────────▶│                      │                       │
 *    │                       │ ⑤ 转发消息           │                       │
 *    │                       │─────────────────────▶│ publishHandler处理     │
 *    │                       │                      │   └→ emqxComponent     │
 *    │                       │                      │      .report() ───────▶│ 消息队列
 *    │                       │                      │                       │
 *
 * 【MQTT Topic 设计约定】
 * 上行（设备→平台）：/sys/{pk}/{dn}/s/{method}
 * 下行（平台→设备）：/sys/{pk}/{dn}/c/{method}
 * 其中：s = server/上行, c = client/下行, pk = productKey, dn = deviceName
 *
 * mqtt官方协议文档：
 *
 * @author sjg
 */
@Slf4j
@Component
@Data
public class EmqxVerticle extends AbstractVerticle implements Runnable {

    /**
     * 认证 Verticle，提供 HTTP 接口供 EMQX Broker 回调进行设备认证和 ACL 鉴权
     * 部署时机：在 run() 方法的定时任务中动态部署
     */
    @Resource
    private AuthVerticle authVerticle;

    /**
     * EMQX 组件实例（ThingComponent 的子类）
     * 负责将设备上行消息通过 report() 方法上报到平台消息队列
     * 也负责接收平台下发的指令，通过 MQTT 发送给设备
     */
    @Resource
    private EmqxComponent emqxComponent;

    /**
     * AuthVerticle 部署后的唯一标识 ID
     * 用于停止时取消部署（vertx.undeploy(deployedId)）
     */
    private String deployedId;

    /**
     * 当前的 MQTT 连接配置
     * 在 startServer() 方法中设置
     */
    private MqttConfig mqttConfig;

    /**
     * MQTT 客户端是否已连接 EMQX Broker
     * 在 client.connect() 成功回调中设为 true，断开/异常时设为 false
     */
    private boolean mqttConnected = false;

    /**
     * AuthVerticle 认证服务是否已启动
     * 在 AuthVerticle 部署成功回调中设为 true
     */
    private boolean authServerStarted = false;

    /**
     * MQTT 客户端实例（Vertx MQTT Client）
     * 用于与 EMQX Broker 建立连接、订阅 Topic、发布消息
     */
    private MqttClient client;

    /**
     * 设备 API，用于设备注册和认证
     * 关键方法：
     * - deviceApi.auth(DeviceAuth): 设备认证（AuthVerticle 中调用）
     * - deviceApi.registerDevice(RegisterDevice): 设备注册（子设备注册时调用）
     */
    @Resource
    private DeviceApi deviceApi;

    /**
     * 定时重连任务是否已启动
     * 保证只有一个定时任务在运行
     */
    private Boolean taskStarted = Boolean.FALSE;

    /**
     * 停止标志，设置为 true 后定时任务不再执行连接逻辑
     * 在 stopServer() 中设置为 true
     */
    private Boolean taskStop = Boolean.FALSE;

    /**
     * 【关键数据结构】ClientID → 设备列表的映射表
     *
     * 用途：当设备断开连接时（收到 /sys/client/disconnected 消息），
     *       根据 clientId 找到对应的所有设备（一个 clientId 可能对应多个设备，
     *       比如网关设备带了多个子设备），批量将这些设备设置为离线状态。
     *
     * Key: productKey + deviceName 的组合（如 "a1B2C3device001"）
     * Value: Set<String>，每个元素是 "productKey,deviceName" 格式
     *
     * 数据写入时机：AuthVerticle 认证成功时（putIfAbsent）
     * 数据读取时机：设备断开连接时（offline 方法）
     */
    public static final Map<String, Set<String>> CLIENT_DEVICE_MAP = new HashMap<>();

    /**
     * 定时连接管理任务的线程池（单线程）
     * 每 3 秒执行一次 run() 方法，检查认证服务和 MQTT 连接状态
     */
    private ScheduledThreadPoolExecutor emqxConnectTask = ThreadUtil.newScheduled(1, "emqx_connect");


    /**
     * 启动 EMQX MQTT 连接服务
     *
     * 【调用时机】
     * 由 EmqxComponent.stateChange() 调用，当平台下发组件配置或启用组件时触发。
     *
     * 【启动流程详解】
     * 1. 生成随机服务端密码（serverPassword），作为 EMQX HTTP 认证回调的凭证
     *    - 作用：AuthVerticle 收到 EMQX 的认证请求时，用此密码验证请求方是否是本服务端
     *    - 安全设计：每次调用 startServer() 都生成新密码，防止旧密码泄露
     *
     * 2. 配置 MQTT 客户端选项：
     *    - clientId = "server"：固定标识，在 AuthVerticle 中用于识别服务端连接
     *    - username = "server"：同上
     *    - password = serverPassword：认证密码
     *    - cleanSession = true：每次连接都是干净会话，不保留离线消息
     *    - maxInflightQueue = 10000：最大并发发送队列，适合高吞吐场景
     *    - keepAliveInterval = 60：心跳间隔 60 秒
     *
     * 3. 将认证端口和密码传给 AuthVerticle，准备启动认证服务
     *
     * 4. 启动定时重连任务（每 3 秒执行一次 run() 方法）
     *    - 首次延迟 3 秒，之后每隔 3 秒执行
     *    - run() 方法内会先检查并启动 AuthVerticle，再尝试连接 EMQX
     *
     * 【注意】此时 MQTT 连接尚未建立，只是创建了客户端和定时任务。
     * 真正的连接在 run() 方法中异步完成。
     *
     * @param mqttConfig EMQX Broker 连接配置（host, port, ssl, topics, authPort）
     */
    public void startServer(MqttConfig mqttConfig) {

        // 生成随机 UUID 作为服务端密码
        // EMQX Broker 在设备认证回调时会携带此密码，AuthVerticle 通过比对密码来验证请求来源
        String serverPassword = IdUtil.fastSimpleUUID();
        MqttClientOptions options = new MqttClientOptions()
                .setClientId("server")          // 固定 ClientID，标识这是服务端连接
                .setUsername("server")          // 固定用户名
                .setPassword(serverPassword)    // 随机密码，用于 AuthVerticle 身份验证
                .setCleanSession(true)          // 干净会话，不保留离线消息
                .setMaxInflightQueue(10000)     // 最大 10000 条并发发送消息
                .setKeepAliveInterval(60);      // 心跳间隔 60 秒

        if (mqttConfig.isSsl()) {
            options.setSsl(true)
                    .setTrustAll(true);  // SSL 模式下信任所有证书
        }

        this.mqttConfig = mqttConfig;
        // 创建 MQTT 客户端（此时尚未连接）
        client = MqttClient.create(vertx, options);

        // 将认证端口和密码传递给 AuthVerticle
        // AuthVerticle 会在该端口启动 HTTP 服务，接收 EMQX 的认证回调
        authVerticle.setPort(mqttConfig.getAuthPort());
        authVerticle.setServerPassword(serverPassword);

        // 重置停止标志，允许定时任务运行
        taskStop = false;
        // 首次启动时，启动定时重连任务（每 3 秒执行一次）
        if (!taskStarted) {
            taskStarted = true;
            emqxConnectTask.scheduleWithFixedDelay(this, 3, 3, TimeUnit.SECONDS);
        }
    }


    /**
     * 定时连接管理任务（由 emqxConnectTask 每 3 秒调用一次）
     *
     * 【两阶段初始化设计】
     * 本方法实现了两个阶段的串行初始化：
     *
     * 阶段1：启动 AuthVerticle（认证服务）
     *   - 检查 authServerStarted 标志，未启动则部署 AuthVerticle
     *   - 使用 CountDownLatch 同步等待部署结果（确保认证服务就绪后才进入阶段2）
     *   - 如果启动失败，本次任务直接返回，等待下次重试
     *
     * 阶段2：建立 MQTT 连接并订阅 Topic
     *   - 只有在 authServerStarted=true 且 mqttConnected=false 时才执行
     *   - 连接 EMQX Broker 并订阅配置的 Topic
     *   - 注册 publishHandler 处理设备上行消息
     *   - 注册 closeHandler/exceptionHandler 处理连接断开和异常
     *
     * 【为什么需要同步等待（CountDownLatch）？】
     * deployVerticle 是异步操作，但 MQTT 连接必须在 AuthVerticle 启动后才能建立。
     * 如果不用 CountDownLatch 阻塞等待，可能在 AuthVerticle 还没就绪时就开始连接 EMQX，
     * 导致设备认证回调找不到 HTTP 服务而失败。
     *
     * 【重连机制】
     * 如果 MQTT 连接断开（closeHandler 将 mqttConnected 设为 false），
     * 下次定时任务执行时会自动重新连接。
     */
    @Override
    public void run() {
        // 已设置停止标志，不再执行连接逻辑
        if (taskStop) {
            return;
        }

        // ========== 阶段1：确保 AuthVerticle 认证服务已启动 ==========
        if (!authServerStarted) {
            try {
                // 使用 CountDownLatch 将异步部署变为同步等待
                CountDownLatch countDownLatch = new CountDownLatch(1);
                // 部署 AuthVerticle（Vertx 异步操作）
                Future<String> future = vertx.deployVerticle(authVerticle);
                future.onSuccess((s -> {
                    // 部署成功：记录部署 ID，设置标志，释放锁
                    deployedId = s;
                    countDownLatch.countDown();
                    authServerStarted = true;
                    log.info("start emqx auth plugin success");
                }));
                future.onFailure(e -> {
                    // 部署失败：设置标志为 false，释放锁
                    countDownLatch.countDown();
                    authServerStarted = false;
                    log.error("start emqx auth plugin failed", e);
                });
                // 阻塞等待，直到部署完成（成功或失败）
                countDownLatch.await();
            } catch (Exception e) {
                authServerStarted = false;
                log.error("start emqx auth server failed", e);
            }
        }

        // 认证服务未就绪，跳过本次 MQTT 连接尝试
        if (!authServerStarted) {
            return;
        }

        // ========== 阶段2：建立 MQTT 连接并订阅 Topic ==========
        // 已连接则跳过
        if (mqttConnected) {
            return;
        }

        try {
            // 解析配置中的 Topic 列表（逗号分隔），构建订阅 Map
            // key=Topic, value=QoS等级（1=至少一次）
            String[] topics = mqttConfig.getTopics().split(",");
            Map<String, Integer> subscribes = new HashMap<>(topics.length);
            for (String topic : topics) {
                subscribes.put(topic, 1);
            }

            // 连接 EMQX Broker（异步操作）
            client.connect(mqttConfig.getPort(), mqttConfig.getHost(), s -> {
                if (s.succeeded()) {
                    log.info("client connect success.");
                    mqttConnected = true;
                    // 连接成功后订阅所有配置的 Topic
                    client.subscribe(subscribes, e -> {
                        if (e.succeeded()) {
                            log.info("===>subscribe success: {}", e.result());
                        } else {
                            log.error("===>subscribe fail: ", e.cause());
                        }
                    });

                } else {
                    mqttConnected = false;
                    log.error("client connect fail: ", s.cause());
                }
            })
            // ========== 注册消息处理器 ==========

            // 【上行消息处理器】处理设备上报的各种消息
            .publishHandler(msg -> {
                String topic = msg.topicName();

                // 过滤下行消息：/c/ 表示平台下发给设备的，不应在此处理
                if (topic.contains("/c/")) {
                    return;
                }

                JsonObject payload = msg.payload().toJsonObject();
                log.info("Client received message on [{}] payload [{}] with QoS [{}]", topic, payload, msg.qosLevel());

                try {
                    // 【设备断线处理】
                    // EMQX 系统消息：/sys/client/disconnected
                    if (topic.equals("/sys/client/disconnected")) {
                        offline(payload.getString("clientid"));
                        return;
                    }

                    // 【Topic 解析】从 Topic 中提取 productKey 和 deviceName
                    // Topic 格式：/sys/{pk}/{dn}/s/{method}
                    ThingDevice device = getDevice(topic);
                    if (device == null) {
                        return;
                    }

                    // 有消息上报-设备上线
//                    online(device.getProductKey(), device.getDeviceName());

                    JsonObject defParams = JsonObject.mapFrom(new HashMap<>(0));

                    // 【方法名提取】从 payload 中获取 method 字段
                    // 例如：thing.event.property.post, thing.service.xxx_reply 等
                    String method = payload.getString("method", "");
                    if (StringUtils.isBlank(method)) {
                        return;
                    }
                    JsonObject params = payload.getJsonObject("params", defParams);
                    method = method.toLowerCase();

                    // ========== 消息类型路由 ==========
                    switch (method) {
                        case "thing.lifetime.register":
                            // 【子设备注册】
                            // 网关设备上报子设备信息，平台侧完成子设备注册
                            String subPk = params.getString("productKey");
                            String subDn = params.getString("deviceName");
                            String subModel = params.getString("model");
                            try {
                                // 调用 DeviceApi 注册子设备
                                // parentId = 当前设备的 deviceId（网关设备ID）
                                deviceApi.registerDevice(RegisterDevice.builder()
                                        .productKey(subPk)
                                        .deviceName(subDn)
                                        .model(subModel)
                                        .parentId(Long.valueOf(device.getDeviceId()))
                                        .build());
                                // 注册成功，回复 code=0
                                reply(topic, payload, 0);
                            } catch (Exception e) {
                                log.error("registerDevice error", e);
                                // 注册失败，回复 code=-1
                                reply(topic, new JsonObject(), -1);
                            }
                            return;
                        case "thing.event.property.post":
                            // 【属性上报】
                            // 设备上报属性值，通过 EmqxComponent.report() 将消息发送到平台消息队列
                            emqxComponent.report(PropertyReport.builder()
                                    .productKey(device.getProductKey())
                                    .deviceName(device.getDeviceName())
                                    .params(params.getMap())
                                    .build());
                            // 回复成功
                            reply(topic, payload, 0);
                            break;
                        default:
                            // 通用事件上报（thing.event.xxx）
                            if (method.startsWith("thing.event.")) {
                                // 提取事件名（去掉 "thing.event." 前缀）
                                emqxComponent.report(EventReport.builder()
                                        .name(method.replace("thing.event.", ""))
                                        .params(params.getMap())
                                        .build());
                                reply(topic, payload, 0);
                            } else if (method.startsWith("thing.service.") && method.endsWith("_reply")) {
                                // 【服务调用回复】
                                // 设备对平台下发的服务调用进行回复
                                // 例如 method="thing.service.getTime_reply" → name="getTime"
                                emqxComponent.report(ServiceReply.builder()
                                        .name(method.replaceAll("thing\\.service\\.(.*)_reply", "$1"))
                                        .code(payload.getInteger("code", 0))
                                        .params(params.getMap())
                                        .build());
                            }
                    }

                } catch (Exception e) {
                    log.error("message is illegal.", e);
                }
            })
            // 【连接断开处理器】MQTT 连接断开时，重置连接标志，定时任务会自动重连
            .closeHandler(e -> {
                mqttConnected = false;
                log.info("client closed:", e);
            })
            // 【异常处理器】MQTT 连接异常时的处理
            .exceptionHandler(event -> log.error("client fail", event));
        } catch (Exception e) {
            log.error("start emqx client failed", e);
        }

    }

    /**
     * 从 MQTT Topic 中解析设备信息
     *
     * Topic 格式约定：/sys/{productKey}/{deviceName}/{direction}/{method...}
     *   索引:  [0]  [1]    [2]         [3]         [4]       [5...]
     *
     * 示例：/sys/a1B2C3/device001/s/thing.event.property.post
     *       → productKey = "a1B2C3", deviceName = "device001"
     *
     * @param topic MQTT Topic 字符串
     * @return ThingDevice 包含 productKey 和 deviceName，Topic 层级不足时返回 null
     */
    public ThingDevice getDevice(String topic) {
        String[] topicParts = topic.split("/");
        // Topic 层级不足 5 层，无法提取 pk/dn
        if (topicParts.length < 5) {
            return null;
        }
        return ThingDevice.builder()
                .productKey(topicParts[2])   // 第3段：产品Key
                .deviceName(topicParts[3])   // 第4段：设备名称
                .build();
    }

    /**
     * 设备上线通知
     *
     * 通过 EmqxComponent.report() 将设备上线事件发送到平台消息队列，
     * 平台收到后会更新设备状态为 ONLINE。
     *
     * @param pk 产品Key
     * @param dn 设备名称
     */
    public void online(String pk, String dn) {
        emqxComponent.
                report(DeviceStateChange.builder()
                        .productKey(pk)
                        .deviceName(dn)
                        .state(DeviceState.ONLINE)
                        .build());
    }


    /**
     * 回复设备（数组参数版本）
     *
     * 与 reply() 的区别：data 字段使用 payload 中的 "params" JsonArray，而非 JsonObject。
     * 适用于服务调用回复时返回数组类型数据。
     *
     * 【Topic 转换规则】
     * 将上行 Topic 转换为下行 Topic：
     *   /sys/{pk}/{dn}/s/{method} → /sys/{pk}/{dn}/c/{method}_reply
     *   例如：/sys/a1B2C3/device001/s/thing.service.getList
     *     →  /sys/a1B2C3/device001/c/thing.service.getList_reply
     *
     * @param topic   原始上行 Topic
     * @param payload 原始消息内容（用于提取 id、method 等字段）
     * @param code    回复状态码（0=成功，-1=失败）
     */
    private void replyArray(String topic, JsonObject payload, int code) {
        Map<String, Object> payloadReply = new HashMap<>();
        payloadReply.put("id", payload.getString("id"));
        payloadReply.put("method", payload.getString("method") + "_reply");
        payloadReply.put("code", code);
        payloadReply.put("data", payload.getJsonArray("params"));

        // 将 /s/ 替换为 /c/ 并添加 _reply 后缀，表示这是下行回复消息
        topic = topic.replace("/s/", "/c/") + "_reply";

        String finalTopic = topic;
        client.publish(topic, JsonObject.mapFrom(payloadReply).toBuffer(), MqttQoS.AT_MOST_ONCE, false, false)
                .onSuccess(h -> {
                    log.info("publish {} success", finalTopic);
                });
    }

    /**
     * 设备断线处理
     *
     * 【处理流程】
     * 1. 从 clientId 中提取 productKey 和 deviceName（格式：{pk}_{dn}_{model}）
     * 2. 通过 CLIENT_DEVICE_MAP 查找该 clientId 对应的所有设备
     * 3. 将所有关联设备设置为 OFFLINE 状态
     *
     * 【为什么一个 clientId 可能对应多个设备？】
     * 网关场景下，一个 MQTT 连接（clientId）可能承载多个子设备。
     * 当网关断开时，所有子设备也应该被标记为离线。
     *
     * 【clientId 格式说明】
     * - 网关/设备 clientId：{productKey}_{deviceName}_{model}
     *   例如：a1B2C3_gateway001_GW100
     * - 提取规则：parts[0]=productKey, parts[1]=deviceName
     *
     * @param clientId 断开连接的 MQTT 客户端 ID
     */
    public void offline(String clientId) {
        // 解析 clientId：格式为 {pk}_{dn}_{model}
        String[] parts = clientId.split("_");
        if (parts.length < 2) {
            return;
        }
        // 从 CLIENT_DEVICE_MAP 中查找该连接对应的所有设备
        // Key 为 productKey + deviceName（不带下划线拼接）
        Set<String> devices = CLIENT_DEVICE_MAP.get(parts[0] + parts[1]);
        if (CollectionUtil.isEmpty(devices)) {
            return;
        }
        // 批量将设备设置为离线状态
        for (String device : devices) {
            String[] pkDn = device.split(",");

            emqxComponent.report(DeviceStateChange.builder()
                    .productKey(pkDn[0])
                    .deviceName(pkDn[1])
                    .state(DeviceState.OFFLINE)
                    .build());
        }
    }

    /**
     * 回复设备（标准版本）
     *
     * 【用途】
     * 对设备上报的消息进行回复确认，告诉设备消息已被平台接收处理。
     *
     * 【回复消息格式】
     * {
     *   "id": "原消息ID",
     *   "method": "原method_reply",     // 例如 thing.event.property.post_reply
     *   "code": 0,                       // 0=成功，-1=失败
     *   "data": {原消息的params}
     * }
     *
     * 【Topic 转换规则】
     * 上行 Topic → 下行 Topic：
     *   /sys/{pk}/{dn}/s/{method} → /sys/{pk}/{dn}/c/{method}_reply
     *   s = server(上行), c = client(下行)
     *
     * @param topic   原始上行 Topic
     * @param payload 原始消息内容
     * @param code    回复状态码（0=成功，-1=失败）
     */
    private void reply(String topic, JsonObject payload, int code) {
        Map<String, Object> payloadReply = new HashMap<>();
        payloadReply.put("id", payload.getString("id"));
        payloadReply.put("method", payload.getString("method") + "_reply");
        payloadReply.put("code", code);
        payloadReply.put("data", payload.getJsonObject("params"));
        // 将 /s/ 替换为 /c/ 并添加 _reply 后缀
        topic = topic.replace("/s/", "/c/") + "_reply";

        String finalTopic = topic;
        client.publish(topic, JsonObject.mapFrom(payloadReply).toBuffer(), MqttQoS.AT_MOST_ONCE, false, false)
                .onSuccess(h -> {
                    log.info("publish {} success", finalTopic);
                });
    }

    /**
     * 停止 EMQX 服务
     *
     * 【停止流程】
     * 1. 设置 taskStop=true，阻止定时任务继续执行
     * 2. 取消部署 AuthVerticle（关闭认证 HTTP 服务）
     * 3. 断开 MQTT 客户端连接
     *
     * 【注意】停止后 taskStarted 不会重置，如果再次调用 startServer()，
     * 定时任务会继续使用同一个线程池执行。
     */
    public void stopServer() {
        // 第一步：停止定时任务
        taskStop = true;
        try {
            log.info("emqx component close");
            // 第二步：取消部署 AuthVerticle，关闭认证服务
            if (deployedId != null) {
                CountDownLatch wait = new CountDownLatch(1);
                Future<Void> future = vertx.undeploy(deployedId);
                future.onSuccess(unused -> {
                    log.info("emqx component stopped success");
                    wait.countDown();
                    deployedId = null;
                    authServerStarted = false;
                });
                future.onFailure(h -> {
                    log.error("emqx component stopped failed", h);
                    wait.countDown();
                });
                // 等待最多 5 秒完成 AuthVerticle 的卸载
                wait.await(5, TimeUnit.SECONDS);
            }
            // 第三步：断开 MQTT 客户端连接
            if (client != null) {
                client.disconnect()
                        .onSuccess(unused -> {
                            mqttConnected = false;
                            log.info("stop emqx connect success");
                        })
                        .onFailure(unused -> log.error("stop emqx connect failure"));
            }

        } catch (Throwable e) {
            log.error("emqx plugin stop error", e);
        }
    }

    /**
     * Verticle 停止生命周期回调
     * 关闭 MQTT 连接并关闭定时任务线程池
     */
    @Override
    public void stop() {
        stopServer();
        // 关闭定时任务线程池
        if (emqxConnectTask != null && !emqxConnectTask.isShutdown()) {
            emqxConnectTask.shutdown();
        }
    }
}
