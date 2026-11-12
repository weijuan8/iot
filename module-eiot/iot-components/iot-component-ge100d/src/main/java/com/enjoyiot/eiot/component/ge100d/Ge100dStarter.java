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
package com.enjoyiot.eiot.component.ge100d;

import com.enjoyiot.eiot.component.ge100d.service.Ge100dVerticle;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/***
 * yuange：
 *  ==========关于ge100等系列组件的全面设计思路============
 *  1 属性上报
 *     1) 物模型定义
 *     2）上报到协议层做数据解析(协议层就是针对当前硬件的，不用的硬件需要开发不同的协议)
 *     3）封装成上游需要的格式推送上去
 *
 *  2 开关控制
 *    1）物模型定义布尔类型 0=关闭 1=打开
 *    2）系统发送0，,1给协议层，协议层转换成 具体的指令给硬件
 *    3 方法或者参数标识符 用 ge_开头 协议这边做二次校验 代表这个参数确实是当前硬件的参数，当前协议能支持
 *
 *
 *
 *
 *
 * **/


/**
 * GE100D 组件启动器
 *
 * 【整体注册流程概述】
 * 整个 GE100D 模块的启动链路分为三层：
 *
 * 第1层 - Ge100dStarter（本类）：Spring Boot 启动入口
 *   ↓ 在 @PostConstruct 阶段创建 Vertx 实例，部署 Ge100dVerticle
 *
 * 第2层 - Ge100dVerticle：MQTT 连接管理的核心 Verticle
 *   ↓ 作为 Vertx Verticle 运行，负责：
 *   ↓   1. 管理 MQTT 客户端连接（连接/断开 MQTT Broker）
 *   ↓   2. 订阅 GE100D 网关相关 Topic
 *   ↓   3. 接收网关上行消息（串口数据、心跳、注册）
 *   ↓   4. 下发串口写命令给网关
 *
 * 第3层 - Ge100dComponent：IoT 组件层（继承 ThingComponent）
 *   ↓ 作为 ThingComponent 的子类，负责：
 *   ↓   1. Modbus RTU 协议编解码
 *   ↓   2. 轮询 Modbus 从机设备
 *   ↓   3. 属性解析与上报
 *   ↓   4. 处理平台下发的属性设置/服务调用指令
 *
 * 【为什么需要三层架构？】
 * - Ge100dStarter: Spring 容器管理的入口，负责生命周期初始化
 * - Ge100dVerticle: 运行在 Vertx 事件循环中，非阻塞处理 MQTT 消息，高性能
 * - Ge100dComponent: 集成到 IoT 组件框架中，统一管理设备上下行通信
 *
 * @see Ge100dVerticle GE100D 核心 Verticle
 * @see com.enjoyiot.eiot.component.ge100d.service.Ge100dComponent 组件层
 */
@Slf4j
@Component
public class Ge100dStarter {

    /**
     * 注入 Ge100dVerticle（核心 MQTT 管理 Verticle）
     * 注意：虽然 Ge100dVerticle 也是 @Component，但这里通过 @Resource 注入的是 Spring 管理的同一个实例
     */
    @Resource
    private Ge100dVerticle ge100dVerticle;

    /**
     * Spring Bean 初始化完成后的回调方法
     * 启动流程：
     * 1. 创建一个新的 Vertx 实例（Vertx 是 Vert.x 框架的核心，管理事件循环线程池）
     * 2. 将 Ge100dVerticle 部署到这个 Vertx 实例上
     * 3. 部署成功后，Ge100dVerticle 会进入等待状态，等待外部调用 startServer() 启动真正的 MQTT 连接
     *
     * 注意：此时只是完成了 Verticle 的部署，MQTT 连接尚未建立。
     * 真正的 MQTT 连接是在 Ge100dComponent.stateChange() 被平台调用时，
     * 通过 Ge100dVerticle.startServer() 触发的。
     * 这是一种"延迟连接"的设计：组件先注册到框架，等平台下发配置后再真正连接 MQTT Broker。
     */
    @PostConstruct
    public void init() {
        // 创建 Vertx 实例（默认使用系统可用处理器数量的工作线程）
        Vertx vertx = Vertx.vertx();
        // 将当前 Ge100dVerticle 实例部署到 Vertx 上
        // deployVerticle 是异步操作，通过回调处理结果
        vertx.deployVerticle(ge100dVerticle, ar -> {
            if (ar.succeeded()) {
                log.info("start ge100d component success!");
            } else {
                log.error("start ge100d component failed", ar.cause());
            }
        });
    }
}
