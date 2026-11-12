/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com | Tel: 19918996474
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot] | Tel: 19918996474
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


import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceAuth;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * EMQX 设备认证与 ACL 鉴权 HTTP 服务
 *
 * 【角色定位】
 * 本类是一个 Vertx Verticle，在指定端口启动 HTTP 服务，
 * 提供两个接口供 EMQX Broker 回调：
 * 1. POST /mqtt/auth  — 设备认证（是否可以连接）
 * 2. POST /mqtt/acl   — ACL 鉴权（是否可以发布/订阅某个 Topic）
 *
 * 【为什么需要独立的 HTTP 认证服务？】
 * EMQX Broker 支持通过 HTTP 回调的方式进行设备认证和 ACL 鉴权。
 * 当设备尝试连接 EMQX 时，EMQX 会向本服务的 /mqtt/auth 接口发送 HTTP 请求，
 * 携带 clientId、username、password 等信息，由本服务决定是否允许连接。
 *
 * 【EMQX 配置关联】
 * 在 EMQX 的配置文件中需要配置 HTTP 认证插件指向本服务：
 *   auth.http.auth_req = http://{本服务IP}:{authPort}/mqtt/auth
 *   auth.http.acl_req  = http://{本服务IP}:{authPort}/mqtt/acl
 *
 * 【认证流程时序】
 *
 *   设备                 EMQX Broker           AuthVerticle(本类)        平台后端
 *  ──────              ────────────           ────────────────        ────────
 *    │                      │                       │                    │
 *    │ ① CONNECT            │                       │                    │
 *    │  (clientId,user,pwd) │                       │                    │
 *    │─────────────────────▶│                       │                    │
 *    │                      │ ② POST /mqtt/auth     │                    │
 *    │                      │  {clientid,username,  │                    │
 *    │                      │   password}           │                    │
 *    │                      │──────────────────────▶│                    │
 *    │                      │                       │ ③ DeviceApi.auth() │
 *    │                      │                       │───────────────────▶│
 *    │                      │                       │◀───────────────────│
 *    │                      │                       │                    │
 *    │                      │ ④ {"result":"allow"}  │                    │
 *    │                      │◀──────────────────────│                    │
 *    │                      │                       │                    │
 *    │ ⑤ CONNACK (成功/拒绝) │                       │                    │
 *    │◀─────────────────────│                       │                    │
 *
 * 【ACL 鉴权流程】
 * 设备每次发布/订阅消息时，EMQX 会调用 /mqtt/acl 接口，
 * 检查该设备是否有权限操作指定的 Topic。
 * 核心校验逻辑：Topic 中必须包含该设备的 productKey 和 deviceName，
 * 确保设备只能操作属于自己的 Topic。
 *
 * 【clientId 格式约定】
 * - 服务端：clientId = "server"（固定值）
 * - UUID 格式设备：clientId 按 "-" 分割后长度为 5（EMQX 默认生成的 UUID）
 * - 网关/设备：clientId = "{productKey}_{deviceName}_{model}"
 *
 * @see EmqxVerticle 调用方，负责部署和管理本 Verticle
 */
@Slf4j
@Component
public class AuthVerticle extends AbstractVerticle {

    /**
     * 设备 API，用于设备认证
     * auth() 方法返回 CommonResult<DeviceInfo>，isError() 为 true 表示认证失败
     */
    @Resource
    private DeviceApi deviceApi;

    /**
     * HTTP 服务实例
     */
    private HttpServer backendServer;

    /**
     * HTTP 服务监听端口（由 EmqxVerticle 通过 MqttConfig.authPort 传入）
     */
    @Setter
    private int port;

    /**
     * 服务端密码（由 EmqxVerticle 随机生成）
     * 用于验证 EMQX 回调请求中携带的 serverPassword 是否正确，
     * 防止未授权的请求调用认证接口
     */
    @Setter
    private String serverPassword;


    /**
     * Verticle 启动入口
     *
     * 【启动流程】
     * 1. 创建 HTTP Server
     * 2. 配置路由（/mqtt/auth 和 /mqtt/acl）
     * 3. 绑定端口启动监听
     *
     * 【设计要点】
     * - 使用 Vertx Router 处理 HTTP 路由
     * - BodyHandler 用于解析 POST 请求的 JSON body
     * - 监听 0.0.0.0 表示接受来自所有网络接口的连接（EMQX 可能部署在不同机器上）
     */
    @Override
    public void start() {
        backendServer = vertx.createHttpServer();

        // 第一步：声明并初始化 Router
        Router backendRouter = Router.router(vertx);
        // 必须添加 BodyHandler 才能解析 POST 请求的 JSON body
        backendRouter.route().handler(BodyHandler.create());

        // ==================== 接口1：设备认证 ====================
        // EMQX 在设备尝试连接时调用此接口，决定是否允许连接
        backendRouter.route(HttpMethod.POST, "/mqtt/auth").handler(rc -> {
            JsonObject json = rc.getBodyAsJson();
            log.info("mqtt auth:{}", json);
            try {
                // EMQX 回调时携带的认证参数
                String clientId = json.getString("clientid");
                String username = json.getString("username");
                String password = json.getString("password");

                // ===== 场景1：服务端连接（本平台的 EmqxVerticle） =====
                // 本平台自己作为 MQTT 客户端连接 EMQX 时，使用固定的 clientId="server"
                // 通过 serverPassword 验证身份，确保是合法的服务端连接
                if (clientId.equals("server") && serverPassword.equals(password)) {
                    httpResult(rc.response(), true);
                    return;
                }

                // ===== 场景2：UUID 格式的客户端 =====
                // EMQX 内部默认会对某些连接使用 UUID 作为 clientId（如 Dashboard、管理工具等）
                // UUID 格式如 "550e8400-e29b-41d4-a716-446655440000"，按 "-" 分割后长度为 5
                // 这类客户端直接放行
                String[] clientIdPart = clientId.split("-");
                if (clientIdPart.length == 5) {
                    httpResult(rc.response(), true);
                    return;
                }

                // ===== 场景3：IoT 设备连接认证（核心逻辑） =====
                // clientId 格式：{productKey}_{deviceName}_{model}
                // 例如：a1B2C3_device001_TempSensor
                String[] parts = clientId.split("_");
                if (parts.length < 3) {
                    log.error("clientid:{}不正确", clientId);
                    httpResult(rc.response(), false);
                    return;
                }

                log.info("MQTT client auth,clientId:{},username:{},password:{}",
                        clientId, username, password);

                // 从 clientId 中提取设备标识
                String productKey = parts[0];  // 产品Key
                String deviceName = parts[1];  // 设备名称
                String gwModel = parts[2];     // 设备型号（此处暂未直接使用）

                // 校验 username 是否与 deviceName 一致（username 应该等于设备名称）
                if (!username.equals(deviceName)) {
                    log.error("username:{}不正确", deviceName);
                    httpResult(rc.response(), false);
                    return;
                }

                // 【核心认证】调用平台 DeviceApi 进行设备认证
                // canRegister=true：允许设备首次连接时自动注册
                // authType=MQTT：标识认证类型为 MQTT
                CommonResult<DeviceInfo> authResult = deviceApi.auth(DeviceAuth.builder()
                        .canRegister(true)
                        .clientId(clientId)
                        .userName(username)
                        .password(password)
                        .authType(DeviceAuth.AUTH_TYPE_MQTT)
                        .build());

                if (authResult.isError()) {
                    log.error("设备认证失败:{}", authResult.getMsg());
                    httpResult(rc.response(), false);
                    return;
                }

                // 【认证成功】将设备信息注册到 CLIENT_DEVICE_MAP
                // 用途：当设备断开连接时，通过 clientId 找到对应的设备，将其状态设为离线
                Set<String> devices = new HashSet<>();
                devices.add(productKey + "," + deviceName);
                EmqxVerticle.CLIENT_DEVICE_MAP.putIfAbsent(productKey + deviceName, devices);

                httpResult(rc.response(), true);
            } catch (Throwable e) {
                httpResult(rc.response(), false);
                log.error("mqtt auth failed", e);
            }
        });

        // ==================== 接口2：ACL 鉴权 ====================
        // EMQX 在设备每次发布/订阅消息时调用此接口，决定是否允许该操作
        backendRouter.route(HttpMethod.POST, "/mqtt/acl").handler(rc -> {
            JsonObject json = rc.getBodyAsJson();
            log.info("mqtt acl:{}", json);
            try {
                String clientId = json.getString("clientid");

                // 场景1：服务端连接，拥有所有权限
                if ("server".equals(clientId)) {
                    httpResult(rc.response(), true);
                    return;
                }

                // 场景2：UUID 格式客户端，放行
                String[] clientIdPart = clientId.split("-");
                if (clientIdPart.length == 5) {
                    httpResult(rc.response(), true);
                    return;
                }

                // ===== 场景3：IoT 设备 ACL 鉴权 =====
                String[] parts = clientId.split("_");
                if (parts.length < 3) {
                    log.error("设备端cilentid格式异常");
                    httpResult(rc.response(), false);
                    return;
                }

                // 【核心鉴权逻辑】Topic 隔离校验
                // 确保设备只能操作属于自己的 Topic，防止设备越权访问其他设备的 Topic
                // Topic 格式：/sys/{productKey}/{deviceName}/...
                // 校验：Topic 中必须包含该设备的 productKey(parts[0]) 和 deviceName(parts[1])
                String topic = json.getString("topic");
                List<String> topicParts = Arrays.asList(topic.split("/"));
                if (!topicParts.contains(parts[0]) || !topicParts.contains(parts[1])) {
                    log.error("设备端cilentid和topic不一致");
                    httpResult(rc.response(), false);
                    return;
                }
                httpResult(rc.response(), true);
            } catch (Throwable e) {
                httpResult(rc.response(), false);
                log.error("mqtt acl failed", e);
            }
        });

        // 启动 HTTP 服务，监听指定端口
        backendServer.requestHandler(backendRouter)
                .listen(port, "0.0.0.0")
                .onSuccess(s -> {
                    log.info("auth server start success,port:{}", s.actualPort());
                }).onFailure(e -> {
                    e.printStackTrace();
                });
    }

    /**
     * 返回 EMQX HTTP 认证/鉴权结果
     *
     * EMQX HTTP 插件要求的响应格式：
     *   {"result": "allow"}  — 允许
     *   {"result": "deny"}   — 拒绝
     *
     * HTTP 状态码始终为 200，通过 JSON body 中的 result 字段区分结果。
     *
     * @param response HTTP 响应对象
     * @param allow    true=允许，false=拒绝
     */
    private void httpResult(HttpServerResponse response, boolean allow) {
        response.putHeader("Content-Type", "application/json");
        response.setStatusCode(200);
        response.end("{\"result\": \"" + (allow ? "allow" : "deny") + "\"}");
    }

    /**
     * Verticle 停止回调，关闭 HTTP 服务
     */
    @Override
    public void stop() throws Exception {
        backendServer.close(voidAsyncResult -> log.info("close emqx auth server..."));
    }
}
