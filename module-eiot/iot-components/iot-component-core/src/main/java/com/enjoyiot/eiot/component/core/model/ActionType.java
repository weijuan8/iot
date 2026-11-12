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

package com.enjoyiot.eiot.component.core.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 【设备物行为类型枚举】- 定义了物联网平台中所有可能的设备交互行为
 *
 * 该枚举将设备与平台之间的交互分为两大类：
 *
 * 【上行行为（设备 → 平台）】- 设备主动上报数据
 *  - AUTH("auth")            认证：设备连接时进行身份认证
 *  - REGISTER("register")     注册：设备首次上线时注册自身信息（型号、版本）
 *  - SUB_REGISTER("sub_register") 子设备注册：网关上报其下子设备信息
 *  - SUB_DEREGISTER("deregister") 子设备注销：网关通知子设备下线
 *  - PING("ping")             心跳：设备定期发送心跳包，保持在线状态
 *  - TOPOLOGY("topology")     拓扑更新：网关上报子设备拓扑关系变化
 *  - STATE_CHANGE("state_change") 状态变更：设备上线/下线通知
 *  - PROPERTY_REPORT("property_report") 属性上报：设备上报遥测数据（温度、湿度等）
 *  - EVENT_REPORT("event_report") 事件上报：设备上报告警/事件（如过热告警）
 *  - SERVICE_REPLY("service_reply") 服务回复：设备响应平台的服务调用请求
 *
 * 【下行行为（平台 → 设备）】- 平台向设备发送指令
 *  - PROPERTY_SET("property_set") 属性设置：平台下发属性值给设备（如设置温度阈值）
 *  - PROPERTY_GET("property_get") 属性获取：平台请求设备上报当前属性值
 *  - SERVICE_INVOKE("service_invoke") 服务调用：平台调用设备能力（如重启、校准）
 *  - CONFIG("config")         配置下发：平台下发配置信息给设备
 *  - TOPO_CHANGE("topo_change") 拓扑变更通知：平台通知网关拓扑关系变更
 *  - OTA("ota")               固件升级：平台下发 OTA 升级包给设备
 *
 * 【消息路由机制】
 *  ThingComponent.report() 方法根据 ActionType 将上行消息转换为 ThingModelMessage，
 *  ThingComponent.onMessage() 方法根据 ThingModelMessage.type 将下行消息分发到对应处理逻辑。
 *  ActionType 是物模型与规则引擎衔接的关键桥梁。
 *
 * @author sjg
 */
@Getter
@AllArgsConstructor
public enum ActionType {

    // ==================== 上行（设备 → 平台）====================

    /** 认证：设备连接时验证身份（clientId、密钥等） */
    AUTH("auth"),

    /** 注册：设备首次上线时上报型号(model)和固件版本(version) */
    REGISTER("register"),

    /** 子设备注销：网关通知平台某个子设备已离线/被移除 */
    SUB_DEREGISTER("deregister"),

    /** 子设备注册：网关上报其挂载的子设备列表 */
    SUB_REGISTER("sub_register"),

    /** 心跳：设备定期发送心跳包，平台以此判断设备是否在线 */
    PING("ping"),

    /** 设备拓扑更新：网关上报子设备拓扑关系变化 */
    TOPOLOGY("topology"),

    /** 在线/离线状态变更：设备主动上报或平台检测到的状态变化 */
    STATE_CHANGE("state_change"),

    /** 属性上报：设备上报遥测数据，如温度=25.5°C、湿度=60% 等 */
    PROPERTY_REPORT("property_report"),

    /** 事件上报：设备上报告警事件，如温度过高、门磁打开等 */
    EVENT_REPORT("event_report"),

    /** 服务回复：设备响应平台的服务调用请求，返回执行结果 */
    SERVICE_REPLY("service_reply"),

    // ==================== 下行（平台 → 设备）====================

    /** 属性设置：平台下发属性配置指令给设备 */
    PROPERTY_SET("property_set"),

    /** 属性获取：平台请求设备上报指定属性的当前值 */
    PROPERTY_GET("property_get"),

    /** 服务调用：平台调用设备能力（如远程重启、固件校准等） */
    SERVICE_INVOKE("service_invoke"),

    /** 配置下发：平台下发配置参数给设备 */
    CONFIG("config"),

    /** 拓扑变更通知：平台通知网关拓扑关系已变更 */
    TOPO_CHANGE("topo_change"),

    /** OTA 固件升级：平台下发升级包信息（版本、下载地址、MD5等） */
    OTA("ota");

    private final String type;

}
