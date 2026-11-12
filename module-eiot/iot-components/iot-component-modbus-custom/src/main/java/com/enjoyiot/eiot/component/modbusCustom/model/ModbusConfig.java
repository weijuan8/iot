package com.enjoyiot.eiot.component.modbusCustom.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 【Modbus 组件配置模型】
 *
 * 【配置来源】
 *  从数据库表 eiot_component 的 config 字段（JSON 字符串）解析得到。
 *  管理员在后台管理页面配置，JSON 格式：
 *  {
 *    "port": 502,     // Modbus TCP 监听端口
 *    "timer": 10      // 轮询间隔（秒）
 *  }
 *
 * ============================================================================
 * 【配置热更新流程】
 *
 *  1. 管理员在后台修改配置 → 更新 eiot_component 表 config 字段
 *  2. ThingComponent.config() 定时轮询（每 5 秒）检测变更
 *  3. 检测到变更 → 调用 ModbusComponent.stateChange(enable, configJson)
 *  4. stateChange() 内部：JsonUtils.parseObject(config, ModbusConfig.class)
 *  5. 先停止旧服务（cancelTasks + closeAllClients + stopServer）
 *  6. 再启动新服务（startServer + readTask + offlineCheckTask）
 *
 * ============================================================================
 * 【字段说明】
 *
 *  port：
 *    - TCP 监听端口，默认 Modbus TCP 标准端口 502
 *    - 注意：1024 以下端口在 Linux 上需要 root 权限
 *
 *  timer：
 *    - readTask 的轮询间隔（秒）
 *    - 每个设备在每次轮询中，按属性列表逐个发送读取命令
 *    - 每个属性间隔 2 秒（ThreadUtil.safeSleep(2000)）
 *    - 例如：timer=10 秒，设备有 5 个属性，则每 10 秒轮询一次，
 *      每次轮询约需 5×2=10 秒（异步执行，实际可能有重叠）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModbusConfig {

    /**
     * TCP 监听端口
     * Modbus TCP 标准端口为 502
     */
    private Integer port;

    /**
     * 轮询间隔（秒）
     * readTask 定时任务的执行周期
     */
    private Integer timer;

}
