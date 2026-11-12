package com.enjoyiot.eiot.component.modbusCustom.service;


import com.enjoyiot.eiot.component.modbusCustom.model.ModbusConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


/**
 * ============================================================================
 * 【Modbus TCP Server 管理器】- Vert.x Verticle 实现
 *
 * 【职责】
 *  管理 Modbus TCP Server 的生命周期（创建、启动、停止）。
 *  本身不处理业务逻辑，只负责 TCP 层的服务器管理。
 *
 * ============================================================================
 * 【架构位置：TCP 传输层】
 *
 *  网关(USR-G770) ──(TCP连接)──> ModbusVerticle(本类) ──> ModbusComponent.handle()
 *                                      │                        │
 *                              创建 NetServer             处理业务逻辑
 *                              监听端口 502              注册/心跳/数据上报
 *
 * ============================================================================
 * 【为什么用 Verticle？】
 *
 *  AbstractVerticle 是 Vert.x 的 Actor 模型单元：
 *  - 运行在 Event Loop 线程上（单线程、非阻塞）
 *  - vertx 实例由 ModbusStarter 注入（通过 deployVerticle）
 *  - NetServer 的 I/O 操作全是异步非阻塞的
 *
 *  Vert.x 的事件循环模型：
 *    Event Loop Thread (1 of N)
 *      ├── 监听 TCP 端口（NetServer.listen）
 *      ├── 接收新连接 → 回调 ModbusComponent.handle(NetSocket)
 *      └── 接收数据 → 回调 NetSocket.handler(bufferConsumer)
 *
 * ============================================================================
 * 【与 ModbusComponent 的关系】
 *
 *  本类是"服务器容器"，ModbusComponent 是"业务处理者"。
 *
 *  startServer(config):
 *    → 创建 NetServer → 设置连接处理器为 modbusComponent
 *    → modbusComponent.setModbusServer(netServer, vertx)
 *      → server.connectHandler(modbusComponent)  // 新连接交给 ModbusComponent.handle()
 *
 *  stopServer():
 *    → 关闭 NetServer → 释放端口 → 不再接受新连接
 *
 * ============================================================================
 * 【配置示例】
 *  ModbusConfig {
 *    port: 502,   // Modbus TCP 默认端口
 *    timer: 10    // 轮询间隔（秒）
 *  }
 *
 * @author lzh
 * @see ModbusComponent 核心业务组件
 * @see com.enjoyiot.eiot.component.modbusCustom.ModbusStarter 启动器
 */
@Slf4j
@Component
@Data
public class ModbusVerticle extends AbstractVerticle {

    /**
     * ModbusComponent 引用（双向绑定）
     *
     * 由 ModbusComponent.stateChange() 中设置：
     *   modbusVerticle.setModbusComponent(this);
     *
     * 作用：
     *  - startServer() 中将 NetServer 的连接处理器设置为 modbusComponent
     *  - 使得所有新 TCP 连接自动由 ModbusComponent.handle() 处理
     */
    private ModbusComponent modbusComponent;

    /**
     * Vert.x TCP Server 实例
     * 负责监听端口、接受连接、管理 Socket
     */
    private NetServer netServer;

    /**
     * 【启动 TCP Server】
     *
     * 执行流程：
     *  1. 根据配置设置端口（默认 Modbus TCP 端口 502）
     *  2. 通过 vertx 创建 NetServer
     *  3. 将 NetServer 注入到 ModbusComponent：
     *     → modbusComponent.setModbusServer(netServer, vertx)
     *       → 内部设置 server.connectHandler(modbusComponent)
     *       → 从此所有新连接都由 ModbusComponent.handle() 处理
     *  4. 启动监听（异步操作）
     *
     * 调用链：
     *  ThingComponent.config() 定时轮询（每5秒）
     *    → ModbusComponent.stateChange(true, config)
     *      → modbusVerticle.startServer(modbusConfig)
     *
     * @param config Modbus 配置（port + timer）
     */
    public void startServer(ModbusConfig config) {
        // 设置 TCP Server 选项（监听端口）
        NetServerOptions options = new NetServerOptions()
                .setPort(config.getPort());

        // 创建 Vert.x TCP Server
        netServer = vertx.createNetServer(options);

        // 【关键】将 NetServer 注入 ModbusComponent
        // setModbusServer 内部会执行 server.connectHandler(modbusComponent)
        // 此后所有 TCP 连接事件都会回调 ModbusComponent.handle(NetSocket)
        modbusComponent.setModbusServer(netServer, vertx);

        // 异步启动监听
        netServer.listen(ar -> {
            if (ar.succeeded()) {
                log.info("MODBUS服务启动成功，监听端口 {}", ar.result().actualPort());
            } else {
                log.error("MODBUS服务启动失败", ar.cause());
            }
        });
    }

    /**
     * 【停止 TCP Server】
     *
     * 执行流程：
     *  1. 关闭 NetServer（异步操作）
     *     - 不再接受新连接
     *     - 现有连接不受影响（需要 ModbusComponent 单独处理）
     *  2. 如果 netServer 为 null，说明从未启动过，打印警告
     *
     * 调用链：
     *  ThingComponent.config() 检测到 status 变为 0（停用）
     *    → ModbusComponent.stateChange(false, config)
     *      → cancelTasks() + closeAllClients() + modbusVerticle.stopServer()
     */
    public void stopServer() {
        if (netServer != null) {
            // 异步关闭 TCP Server
            netServer.close(rst -> {
                if (rst.succeeded()) {
                    log.info("MODBUS服务器已成功关闭");
                } else {
                    log.error("关闭MODBUS服务器失败: {}", rst.cause().getMessage());
                }
            });
        } else {
            log.warn("MODBUS服务器尚未启动或已关闭");
        }
    }
}
