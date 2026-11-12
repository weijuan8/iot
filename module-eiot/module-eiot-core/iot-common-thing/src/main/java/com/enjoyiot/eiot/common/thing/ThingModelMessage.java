/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot]
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
package com.enjoyiot.eiot.common.thing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 【物模型消息】- Enjoy-IoT 平台中设备与平台通信的统一消息格式
 *
 * ThingModelMessage 是整个 IoT 消息流转的核心数据结构，
 * 所有上行（设备→平台）和下行（平台→设备）消息都被封装为此格式。
 *
 * 【消息类型常量（type 字段）】
 *  lifetime:   生命周期消息（设备注册、注销）
 *  state:      状态消息（设备上线/离线）
 *  property:   属性消息（属性上报/获取/设置）
 *  event:      事件消息（设备告警、通知事件）
 *  service:    服务消息（服务调用/回复）
 *  ping:       心跳消息
 *  ota:        OTA 升级消息
 *  config:     配置消息（配置下发/获取）
 *  topo_change:拓扑变更消息（网关子设备关系变化）
 *
 * 【标识符常量（identifier 字段）】
 *  ID_PROPERTY_GET = "get"       属性获取
 *  ID_PROPERTY_SET = "set"       属性设置
 *  ID_PROPERTY_REPORT = "report" 属性上报
 *  ID_CONFIG_GET = "get"         配置获取
 *  ID_ONLINE = "online"          设备上线
 *  ID_OFFLINE = "offline"        设备离线
 *  ID_REGISTER = "register"      设备注册
 *  SERVICE_REPLY_SUFFIX = "_reply" 服务回复后缀
 *
 * 【关键字段说明】
 *  - id：消息唯一标识（UUID），用于请求-响应链路追踪
 *  - mid：消息 ID（与 id 相同），用于消息去重
 *  - deviceId：设备在数据库中的 ID（通过 DeviceApi 查询得到）
 *  - productKey + dn：设备全局唯一标识（pk + dn 组合）
 *  - type：消息类型（决定消息如何被分发处理）
 *  - identifier：标识符（具体操作，如属性上报为 "report"，事件名为具体事件标识）
 *  - data：消息数据（属性上报时为属性 Map，事件上报时为事件参数 Map）
 *  - occurred：设备端事件发生时间戳
 *  - time：消息上报到平台的时间戳
 *  - toClient：是否为下行消息（true=平台下发到设备, false=设备上报到平台）
 *
 * 【消息生命周期】
 *  1. 创建：ThingComponent.report() 或业务模块构造
 *  2. 发布：sendMessage() → 发布到 device_thing topic
 *  3. 消费：RuleDeviceConsumer.handler() → 依次调用 Handler 链
 *  4. 处理：各 Handler 根据 type + identifier 判断是否处理
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThingModelMessage {

    // ==================== 消息类型常量 ====================

    /** 生命周期消息（设备注册/注销） */
    public static final String TYPE_LIFETIME = "lifetime";
    /** 状态消息（设备上线/离线） */
    public static final String TYPE_STATE = "state";
    /** 属性消息（属性上报/获取/设置） */
    public static final String TYPE_PROPERTY = "property";
    /** 事件消息（设备告警、通知事件） */
    public static final String TYPE_EVENT = "event";
    /** 服务消息（服务调用/回复） */
    public static final String TYPE_SERVICE = "service";
    /** 心跳消息 */
    public static final String TYPE_PING = "ping";
    /** OTA 升级消息 */
    public static final String TYPE_OTA = "ota";
    /** 配置消息（配置下发/获取） */
    public static final String TYPE_CONFIG = "config";
    /** 拓扑变更消息 */
    public static final String TYPE_TOPO_CHANGE = "topo_change";

    // ==================== 标识符常量 ====================

    /** 属性获取 */
    public static final String ID_PROPERTY_GET = "get";
    /** 属性设置 */
    public static final String ID_PROPERTY_SET = "set";
    /** 属性上报 */
    public static final String ID_PROPERTY_REPORT = "report";
    /** 配置获取 */
    public static final String ID_CONFIG_GET = "get";
    /** 配置设置 */
    public static final String ID_CONFIG_SET = "set";
    /** 变更 */
    public static final String ID_CHANGE = "change";
    /** 设备上线 */
    public static final String ID_ONLINE = "online";
    /** 设备离线 */
    public static final String ID_OFFLINE = "offline";
    /** 设备注册 */
    public static final String ID_REGISTER = "register";
    /** 服务回复后缀（如 "restart_reply"） */
    public static final String SERVICE_REPLY_SUFFIX = "_reply";

    // ==================== 消息字段 ====================

    /** 消息唯一标识（UUID），用于请求-响应链路追踪 */
    private String id;

    /** 消息 ID（与 id 相同），用于消息去重和追踪 */
    private String mid;

    /** 设备在数据库中的 ID */
    private Long deviceId;

    /** 产品 Key（ProductKey） */
    private String productKey;

    /** 设备名称（DeviceName） */
    private String dn;

    /** 所属用户 ID（多租户隔离） */
    private String uid;

    /**
     * 消息类型
     * lifetime:生命周期  state:状态  property:属性
     * event:事件  service:服务
     */
    private String type;

    /** 标识符（具体操作，如属性上报为 "report"，事件名为具体事件标识） */
    private String identifier;

    /** 消息状态码（0=成功） */
    private int code;

    /** 消息数据（属性上报时为属性 Map，事件上报时为事件参数 Map） */
    private Object data;

    /** 设备端事件发生的时间戳（毫秒） */
    private Long occurred;

    /** 消息上报到平台的时间戳（毫秒） */
    private Long time;

    /** 是否为下行消息（true=平台下发到设备, false/null=设备上报到平台） */
    public Boolean toClient;

    /**
     * 将 data 字段转换为 Map<String, Object>
     * 如果 data 不是 Map 类型，返回空 Map
     */
    public Map<String, Object> dataToMap() {
        Map<String, Object> mapData = new HashMap<>();
        if (data instanceof Map) {
            ((Map<?, ?>) data).forEach((key, value) -> mapData.put(key.toString(), value));
        }
        return mapData;
    }
}
