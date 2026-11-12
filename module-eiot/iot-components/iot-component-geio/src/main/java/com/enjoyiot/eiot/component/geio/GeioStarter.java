package com.enjoyiot.eiot.component.geio;

import com.enjoyiot.eiot.component.geio.service.GeioVerticle;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * GEIO 组件启动器
 *
 * <p>整个 GEIO 模块的启动链路分为三层：</p>
 * <ol>
 *   <li>GeioStarter（本类）：Spring Boot 启动入口，在 {@link PostConstruct} 阶段创建 Vertx 实例并部署 GeioVerticle</li>
 *   <li>GeioVerticle：MQTT 连接管理核心 Verticle，负责连接 MQTT Broker、订阅上行 Topic、下发控制帧</li>
 *   <li>GeioComponent：IoT 组件层（继承 ThingComponent），负责协议编解码、属性上报、控制命令拼接</li>
 * </ol>
 *
 * @see GeioVerticle GEIO 核心 Verticle
 * @see com.enjoyiot.eiot.component.geio.service.GeioComponent 组件层
 */
@Slf4j
@Component
public class GeioStarter {

    @Resource
    private GeioVerticle geioVerticle;

    /**
     * Spring Bean 初始化完成后部署 Verticle。
     * 此时仅完成部署，MQTT 连接由 GeioComponent.stateChange() 触发 startServer() 真正建立（延迟连接设计）。
     */
    @PostConstruct
    public void init() {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(geioVerticle, ar -> {
            if (ar.succeeded()) {
                log.info("【信息】GEIO组件启动成功！");
            } else {
                log.error("============= 【GEIO-错误】GEIO组件启动失败 =============", ar.cause());
            }
        });
    }
}
