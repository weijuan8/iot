package com.enjoyiot.eiot.component.modbusCustom;

import com.enjoyiot.eiot.component.modbusCustom.service.ModbusVerticle;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * ============================================================================
 * 【Modbus 组件启动器】- ModbusComponent 的初始化入口
 *
 * 【职责】
 *  在 Spring 容器初始化完成后（@PostConstruct），创建 Vert.x 实例并部署
 *  ModbusVerticle。这是整个 Modbus 组件能够开始工作的第一步。
 *
 * ============================================================================
 * 【启动流程】
 *
 *  ① Spring 扫描 @Component → 创建 ModbusStarter Bean
 *     → 注入 ModbusVerticle 依赖
 *  ② @PostConstruct 触发 init() 方法
 *  ③ 创建 Vertx 实例（Vert.x 事件循环引擎）
 *     → 调用 vertx.deployVerticle(modbusVerticle)
 *       → ModbusVerticle 的 start() 方法被调用
 *       → 此时 TCP Server 尚未创建（等待 ThingComponent.config() 触发 stateChange）
 *
 * ============================================================================
 * 【为什么用 Verticle 模式？】
 *
 *  Vert.x 的 Verticle 是一种轻量级的 Actor 模型：
 *  - 每个 Verticle 在独立的事件循环线程上运行
 *  - 天然支持非阻塞 I/O（NetSocket、NetServer）
 *  - 适合处理大量并发 TCP 连接（Modbus 网关同时连接上百个设备）
 *
 *  Vert.x 事件循环模型：
 *    Event Loop Thread
 *      → 接收 TCP 数据（非阻塞）
 *      → 回调 DataDecoder.decode() 解析数据包
 *      → 回调 ModbusComponent.handle() 处理业务逻辑
 *
 * ============================================================================
 * 【生命周期】
 *  - init() 只执行一次（Spring Bean 初始化时）
 *  - 如果部署失败，日志记录错误，Modbus 功能不可用
 *  - Vert.x 实例随 JVM 生命周期存在
 *
 * @see ModbusVerticle TCP Server 管理（Verticle 实现）
 * @see com.enjoyiot.eiot.component.modbusCustom.service.ModbusComponent 核心组件
 */
@Slf4j
@Component
public class ModbusStarter {

    /**
     * ModbusVerticle 实例（通过 @Resource 注入）
     * 负责 TCP Server 的创建、启动和停止
     */
    @Resource
    private ModbusVerticle modbusVerticle;

    /**
     * 【组件初始化入口】
     *
     * 在 Spring Bean 初始化完成后自动执行（@PostConstruct）。
     *
     * 执行流程：
     *  1. 创建 Vertx 实例（事件循环引擎）
     *     - Vertx.vertx() 默认使用 CPU 核心数 × 2 个事件循环线程
     *     - 每个线程运行多个 Verticle 实例
     *  2. 部署 ModbusVerticle
     *     - deployVerticle() 是异步的，通过回调通知结果
     *     - 部署成功：ModbusVerticle.start() 被调用
     *     - 部署失败：记录错误日志
     *
     * 注意：此时 TCP Server 尚未创建。
     * TCP Server 的实际启动时机在 stateChange(true, config) 中。
     * 详见 ThingComponent.config() 和 ModbusComponent.stateChange()。
     */
    @PostConstruct
    public void init() {
        // 创建 Vert.x 实例（事件循环引擎）
        Vertx vertx = Vertx.vertx();
        // 部署 ModbusVerticle（异步操作，回调通知结果）
        vertx.deployVerticle(modbusVerticle, ar -> {
            if (ar.succeeded()) {
                log.info("start modbus[custom] component success!");
            } else {
                log.error("start modbus[custom] component failed", ar.cause());
            }
        });
    }
}
