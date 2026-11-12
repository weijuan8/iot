
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
package com.enjoyiot.eiot.component.emqx;


import com.enjoyiot.eiot.component.emqx.service.EmqxVerticle;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * EMQX 组件启动器
 *
 * 【整体注册流程概述】
 * 整个 EMQX 模块的启动链路分为三层：
 *
 * 第1层 - EmqxStarter（本类）：Spring Boot 启动入口
 *   ↓ 在 @PostConstruct 阶段创建 Vertx 实例，部署 EmqxVerticle
 *
 * 第2层 - EmqxVerticle：EMQX 连接管理的核心 Verticle
 *   ↓ 作为 Vertx Verticle 运行，负责：
 *   ↓   1. 管理 MQTT 客户端连接（连接/断开 EMQX Broker）
 *   ↓   2. 部署 AuthVerticle（认证授权 HTTP 服务）
 *   ↓   3. 处理设备上行消息（属性上报、事件上报、服务回复等）
 *   ↓   4. 管理设备上下线状态
 *
 * 第3层 - EmqxComponent：IoT 组件层（继承 ThingComponent）
 *   ↓ 作为 ThingComponent 的子类，负责：
 *   ↓   1. 将平台下发的指令（属性设置、服务调用等）转换为 MQTT 消息发送给设备
 *   ↓   2. 接收 EmqxVerticle 上报的设备数据，通过 report() 方法上报到平台消息队列
 *
 * 【为什么需要三层架构？】
 * - EmqxStarter: Spring 容器管理的入口，负责生命周期初始化
 * - EmqxVerticle: 运行在 Vertx 事件循环中，非阻塞处理 MQTT 消息，高性能
 * - EmqxComponent: 集成到 IoT 组件框架中，统一管理设备上下行通信
 *
 * @see EmqxVerticle EMQX 核心 Verticle
 * @see com.enjoyiot.eiot.component.emqx.service.EmqxComponent 组件层
 */
@Slf4j
@Component
public class EmqxStarter {

    /**
     * 注入 EmqxVerticle（核心 MQTT 管理 Verticle）
     * 注意：虽然 EmqxVerticle 也是 @Component，但这里通过 @Resource 注入的是 Spring 管理的同一个实例
     */
    @Resource
    private EmqxVerticle emqxVerticle;

    /**
     * Spring Bean 初始化完成后的回调方法
     * 启动流程：
     * 1. 创建一个新的 Vertx 实例（Vertx 是 Vert.x 框架的核心，管理事件循环线程池）
     * 2. 将 EmqxVerticle 部署到这个 Vertx 实例上
     * 3. 部署成功后，EmqxVerticle 会进入等待状态，等待外部调用 startServer() 启动真正的 MQTT 连接
     *
     * 注意：此时只是完成了 Verticle 的部署，MQTT 连接尚未建立。
     * 真正的 MQTT 连接是在 EmqxComponent.stateChange() 被平台调用时，通过 EmqxVerticle.startServer() 触发的。
     * 这是一种"延迟连接"的设计：组件先注册到框架，等平台下发配置后再真正连接 EMQX Broker。
     */
    @PostConstruct
    public void init() {
        // 创建 Vertx 实例（默认使用系统可用处理器数量的工作线程）
        Vertx vertx = Vertx.vertx();
        // 将当前 EmqxVerticle 实例部署到 Vertx 上
        // deployVerticle 是异步操作，通过回调处理结果
        vertx.deployVerticle(emqxVerticle, ar -> {
            if (ar.succeeded()) {
                log.info("start emqx component success!");
            } else {
                log.error("start emqx component failed", ar.cause());
            }
        });
    }
}
