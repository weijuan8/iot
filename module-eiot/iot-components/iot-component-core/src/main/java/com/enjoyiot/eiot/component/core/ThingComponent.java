
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
package com.enjoyiot.eiot.component.core;

import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.component.core.model.ActionType;
import com.enjoyiot.eiot.common.enums.DeviceState;
import com.enjoyiot.eiot.component.core.model.down.*;
import com.enjoyiot.eiot.component.core.model.up.*;
import com.enjoyiot.module.eiot.api.component.dto.ComponentInfo;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.enjoyiot.eiot.common.enums.ErrorCodeConstants.PARAMS_EXCEPTION;
import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

@Slf4j
public abstract class ThingComponent extends AbstractComponent {

    /**
     * 缓存的组件配置信息
     * 包含组件状态（启用/停用）和配置 JSON 字符串
     *
     * 【配置来源】
     *  通过 ComponentApi.getInfo(getType()) 从数据库表 eiot_component 读取，
     *  读取到的 ComponentInfo 包含：
     *    - status：0=停用，1=启用
     *    - config：JSON 配置字符串（如 {"host":"127.0.0.1","port":1883,...}）
     *    - name：组件名称（仅用于显示）
     */
    private ComponentInfo componentInfo;

    protected ThingComponent(ComponentServices componentServices) {
        super(componentServices);
    }

    /**
     * 【定时获取组件配置】- 每 5 秒从平台拉取最新配置
     *
     * ============================================================================
     * 【这是组件"配置热更新"的核心机制】
     * 管理员在后台修改组件的启用状态或配置参数后，不需要重启整个系统，
     * 组件会在最多 5 秒内自动感知变更并应用新配置。
     *
     * 【数据流】
     *  后台管理页 → ComponentController.update() → 更新 eiot_component 表
     *    → ThingComponent.config() 定时轮询（每 5 秒）
     *      → ComponentApiImpl.getInfo(type) → ComponentService.getComponent(type)
     *        → ComponentMapper.selectOne(ComponentDO::getType, type)
     *          → 返回 ComponentInfo{status, config, name}
     *
     * 【配置变更检测逻辑】
     *  1. 调用 ComponentApi.getInfo(getType()) 获取最新配置
     *  2. 如果配置为空 → 跳过（组件可能尚未在数据库中创建）
     *  3. 如果是首次获取（componentInfo == null）→ 直接调用 stateChange() 应用配置
     *  4. 如果 status 或 config 有变化 → 调用 stateChange() 应用新配置
     *  5. 如果无变化 → 跳过，避免不必要的重启
     *
     * 【配置变更场景】
     *  - 管理员在后台启用了组件（status: 0→1）
     *    → stateChange(true, config) → 解析配置 → 启动协议服务
     *  - 管理员修改了组件配置参数（如 MQTT Broker 地址变更）
     *    → stateChange(true, newConfig) → 先停旧服务 → 再启新服务
     *  - 管理员停用了组件（status: 1→0）
     *    → stateChange(false, config) → 停止协议服务
     *
     * 【子类实现 stateChange() 的通用模式】
     *  if (!enable) {
     *      verticle.stopServer();  // 停止协议服务
     *      return true;
     *  }
     *  Config cfg = JsonUtils.parseObject(config, Config.class);  // 解析 JSON 配置
     *  verticle.stopServer();     // 先停旧
     *  verticle.startServer(cfg);  // 再启新
     *  return true;
     *
     * 【为什么是 5 秒？】
     *  权衡实时性与资源消耗。5 秒延迟对 IoT 场景来说可接受，
     *  同时避免了过于频繁的数据库查询。
     */
    @Scheduled(fixedRate = 5000)
    public void config() {
        ComponentInfo info = componentServices.getComponentApi().getInfo(getType());
        if (info == null || info.getConfig() == null) {
            return;
        }
        // 非初次启动，且状态和配置均未变更 → 跳过
        if (componentInfo != null
                && componentInfo.getStatus().equals(info.getStatus())
                && componentInfo.getConfig().equals(info.getConfig())) {
            return;
        }
        // 配置变更，通知子类处理
        boolean changed = stateChange(info.getStatus() == 1, info.getConfig());
        if (changed) {
            // 变更成功后缓存新配置，下次以此作为比较基准
            componentInfo = info;
        }
    }

    /**
     * 组件状态变更回调
     *
     * @param enable 是否启用（status == 1 为启用）
     * @param config 组件配置 JSON 字符串
     * @return true 表示变更成功
     */
    protected abstract boolean stateChange(boolean enable, String config);

    /**
     * 【物模型消息分发】- 根据消息类型将下行消息路由到对应处理方法
     *
     * 这是下行消息（平台 → 设备）在组件侧的**统一入口**。
     * 消息从 ComponentManager 发布到 {componentType}_device_thing topic 后，
     * 由 AbstractComponent.handler() 进行路由匹配（确认设备属于本组件），
     * 匹配成功后调用此方法进行消息类型分发。
     *
     * 【消息类型 → 处理方法映射】
     *  ┌─────────────────┬──────────────────────┬──────────────────────────┐
     *  │ type             │ 处理方法              │ 构造的下行 Action 对象    │
     *  ├─────────────────┼──────────────────────┼──────────────────────────┤
     *  │ "service"        │ doServiceInvoke()    │ ServiceInvoke             │
     *  │ "property"       │ doProperty()         │ PropertyGet / PropertySet │
     *  │ "config"         │ doConfig()           │ DeviceConfig              │
     *  │ "ota"            │ doOta()              │ DeviceOta                 │
     *  │ "topo_change"    │ topoChange()         │ DeviceTopoChange          │
     *  └─────────────────┴──────────────────────┴──────────────────────────┘
     *
     * 【与 report() 的对应关系】
     *  - onMessage()：处理**下行**消息（平台→设备），将 ThingModelMessage 拆解为具体 Action
     *  - report()：处理**上行**消息（设备→平台），将 Action 封装为 ThingModelMessage
     *
     * 【完整的下行链路（从本方法到设备）】
     *  onMessage()
     *    → doXxx()（解析消息、构造 Action 对象）
     *      → 子类实现（如 EmqxComponent.serviceInvoke()）
     *        → MQTT publish（通过 Broker 下发给设备）
     *
     * @param message 物模型消息（来自消息队列，已通过路由匹配）
     */
    /**
     * 【下行消息类型分发】- 根据 message.type 将消息路由到对应的处理方法
     *
     * <p>这是 AbstractComponent.handler() 路由匹配成功后调用的分发方法。
     * 根据不同消息类型，解析为具体的 Action 对象，调用子类的协议实现方法下发。</p>
     *
     * <h3>类型 → 方法映射</h3>
     * <table>
     *   <tr><th>type</th><th>分发方法</th><th>场景</th></tr>
     *   <tr><td>"service"</td><td>doServiceInvoke() → serviceInvoke()</td><td>★ 控制开关/继电器等</td></tr>
     *   <tr><td>"property"</td><td>doProperty() → propertyGet/Set()</td><td>读取/修改设备属性</td></tr>
     *   <tr><td>"config"</td><td>doConfig() → deviceConfig()</td><td>下发配置参数</td></tr>
     *   <tr><td>"ota"</td><td>doOta() → deviceOta()</td><td>固件升级</td></tr>
     *   <tr><td>"topo_change"</td><td>topoChange()</td><td>网关子设备拓扑变更</td></tr>
     * </table>
     *
     * @param message ThingModelMessage（由 DeviceCtrlServiceImpl.send() 构造）
     */
    @Override
    public void onMessage(ThingModelMessage message) {
        String type = message.getType(); // 消息类型：service/property/config/ota/topo_change
        switch (type) {
            case ThingModelMessage.TYPE_SERVICE:
                // 服务调用：用户通过小程序/Web 点击按钮触发
                // → doServiceInvoke() 解析 message 为 ServiceInvoke 对象
                //   → Ge100dComponent.serviceInvoke() 提取 raw_ 命令
                //     → Ge100dVerticle.publishCommand() MQTT 下发
                doServiceInvoke(message);
                break;
            case ThingModelMessage.TYPE_PROPERTY:
                // 属性操作：identifier="set" → 属性设置, identifier="get" → 属性获取
                doProperty(message);
                break;
            case ThingModelMessage.TYPE_CONFIG:
                // 配置下发：将平台配置参数推送给设备
                doConfig(message);
                break;
            case ThingModelMessage.TYPE_OTA:
                // OTA 升级：下发固件升级包下载地址、版本、MD5 等信息
                doOta(message);
                break;
            case ThingModelMessage.TYPE_TOPO_CHANGE:
                // 拓扑变更：通知网关子设备绑定/解绑关系变化
                topoChange(message);
                break;
        }
    }

    /**
     * 【处理服务调用】- 解析 ThingModelMessage 为 ServiceInvoke 对象
     *
     * 消息格式要求：message.data 必须是 Map 类型（服务参数）
     * 提取字段：
     *  - name = message.identifier（服务名称）
     *  - params = message.dataToMap()（服务参数）
     *
     * 转换后调用子类的 serviceInvoke() 方法执行具体逻辑
     */
    /**
     * 【解析服务调用消息 → ServiceInvoke 对象 → 调用子类 serviceInvoke()】
     *
     * <p>将 ThingModelMessage 的各字段提取到 ServiceInvoke 对象中，
     * 然后调用子类（如 Ge100dComponent）的 serviceInvoke() 执行实际下发。</p>
     *
     * <h3>字段映射</h3>
     * <pre>
     * ThingModelMessage          →  ServiceInvoke
     * ─────────────────────────────────────────────
     * message.getId()            →  id          （消息追踪ID）
     * message.getIdentifier()    →  name        （服务名称，如 "switch_control"）
     * message.getProductKey()    →  productKey  （产品Key）
     * message.getDn()            →  deviceName  （设备名称）
     * message.dataToMap()        →  params      （参数 Map，如 {"raw_":"RELAY1_ON"}）
     * </pre>
     *
     * @param message ThingModelMessage（type="service"），由 DeviceCtrlServiceImpl.send() 构造
     */
    private void doServiceInvoke(ThingModelMessage message) {
        // data 必须是 Map 类型（如 {"raw_": "RELAY1_ON"}）
        if (!(message.getData() instanceof Map)) {
            throw exception(PARAMS_EXCEPTION);
        }
        // 构造 ServiceInvoke 对象，传递给子类的 serviceInvoke() 方法
        // 子类（Ge100dComponent）在 serviceInvoke() 中提取 raw_ 参数，通过 MQTT 下发
        serviceInvoke(ServiceInvoke.builder()
                .id(message.getId())              // 消息ID，用于链路追踪
                .name(message.getIdentifier())     // 服务名 = message.identifier
                .productKey(message.getProductKey()) // 产品Key
                .deviceName(message.getDn())       // 设备名称
                .params(message.dataToMap())       // 参数 Map → {"raw_": "RELAY1_ON"}
                .build());
    }

    /**
     * 【处理属性操作】- 根据 identifier 区分属性获取和属性设置
     *
     * identifier = "get" → PropertyGet（属性获取）
     *   用户请求读取设备当前属性值，data 为属性标识符列表 List<String>
     *
     * identifier = "set" → PropertySet（属性设置）
     *   用户修改设备属性值，data 为属性键值对 Map<String, Object>
     *
     * 例如：
     *  属性获取：{"identifier":"get", "data":["temperature","humidity"]}
     *  属性设置：{"identifier":"set", "data":{"target_temp":26}}
     */
    private void doProperty(ThingModelMessage message) {
        String identifier = message.getIdentifier();
        if (ThingModelMessage.ID_PROPERTY_GET.equals(identifier)) {
            if (!(message.getData() instanceof List)) {
                throw exception(PARAMS_EXCEPTION);
            }
            // 属性获取：提取属性 key 列表
            propertyGet(PropertyGet.builder()
                    .id(message.getId())
                    .productKey(message.getProductKey())
                    .deviceName(message.getDn())
                    .keys((List<String>) message.getData())
                    .build());
        } else if (ThingModelMessage.ID_PROPERTY_SET.equals(identifier)) {
            // 属性设置：提取属性键值对
            propertySet(PropertySet.builder()
                    .id(message.getId())
                    .productKey(message.getProductKey())
                    .deviceName(message.getDn())
                    .params(message.dataToMap())
                    .build());
        }
    }

    /**
     * 【处理配置下发】- 将配置参数下发给设备
     *
     * message.data 为配置键值对 Map，直接透传给 deviceConfig()
     */
    private void doConfig(ThingModelMessage message) {
        deviceConfig(DeviceConfig.builder()
                .id(message.getId())
                .productKey(message.getProductKey())
                .deviceName(message.getDn())
                .config(message.dataToMap())
                .build());
    }

    /**
     * 【通知网关设备拓扑图变化】
     *
     * 当平台侧修改了网关-子设备拓扑关系后，
     * 通过此方法通知网关设备更新其本地拓扑信息。
     *
     * @param action 包含拓扑变更数据的 ThingModelMessage
     */
    protected void topoChange(ThingModelMessage action) {
        deviceTopoChange(DeviceTopoChange.builder()
                .productKey(action.getProductKey())
                .deviceName(action.getDn())
                .params(action.dataToMap())
                .build());
    }


    /**
     * 【处理 OTA 升级】- 将升级包信息下发给设备
     *
     * message.data 为 OTA 升级数据（OtaData 对象），直接透传给 deviceOta()
     */
    private void doOta(ThingModelMessage message) {
        deviceOta(DeviceOta.builder().id(message.getId())
                .productKey(message.getProductKey())
                .deviceName(message.getDn())
                .data(message.getData())
                .build()
        );
    }

    // ==================== 下行抽象方法（子类实现具体协议交互）====================

    /** 下发 OTA 升级指令 */
    protected abstract void deviceOta(DeviceOta action);

    /** 下发服务调用指令 */
    protected abstract void serviceInvoke(ServiceInvoke action);

    /** 下发属性获取指令 */
    protected abstract void propertyGet(PropertyGet action);

    /** 下发属性设置指令 */
    protected abstract void propertySet(PropertySet action);

    /** 下发设备配置 */
    protected abstract void deviceConfig(DeviceConfig action);

    /** 下发拓扑变更通知 */
    protected abstract void deviceTopoChange(DeviceTopoChange action);

    /**
     * 【上行消息统一入口】- 将设备上报的 Action 对象转换为 ThingModelMessage 并发送
     *
     * 这是上行消息（设备→平台）的统一入口，所有设备上报行为都通过此方法进入系统。
     *
     * 【处理流程】
     *  1. 根据 ActionType 进行 switch 分发，将具体 Action 对象转换为 ThingModelMessage：
     *     - EVENT_REPORT    → ThingModelMessage(type="event", identifier=事件名, data=事件参数)
     *     - PROPERTY_REPORT → ThingModelMessage(type="property", identifier="report", data=属性Map)
     *     - REGISTER        → ThingModelMessage(type="lifetime", identifier="register", data={model, version})
     *     - STATE_CHANGE    → ThingModelMessage(type="state", identifier="online"/"offline")
     *     - SERVICE_REPLY   → ThingModelMessage(type="service", identifier=服务名+"_reply", data=回复参数)
     *     - SUB_REGISTER    → ThingModelMessage(type="lifetime", identifier="register", data={model, version, subs})
     *
     *  2. 填充通用字段：
     *     - id：如果 Action 有 id 则沿用，否则生成 UUID
     *     - time/occurred：时间戳
     *     - mid：消息 ID（与 id 相同）
     *     - dn/productKey：设备标识
     *     - deviceId：通过 DeviceApi 查询设备表获取
     *
     *  3. 调用 sendMessage() 发布到 device_thing topic
     *     - sendMessage 内部会更新 Redis 设备路由缓存
     *     - 消息被规则引擎消费：日志入库 → 属性存储 → 状态检查 → 规则匹配
     *
     * 【消息转换示例】
     *  输入：PropertyReport{pk="prod_001", dn="dev_001", params={"temp":25.5}}
     *  输出：ThingModelMessage{type="property", identifier="report", data={"temp":25.5}}
     *
     * @param action 设备上报的行为对象（PropertyReport、EventReport 等）
     */
    public void report(ReportAction action) {
        ActionType type = action.getType();
        ThingModelMessage message = null;

        // 【核心】根据 ActionType 将 Action 对象转换为 ThingModelMessage
        switch (type) {
            case EVENT_REPORT:
                // 事件上报：name → identifier, params → data
                message = getEventReport((EventReport) action);
                break;
            case PROPERTY_REPORT:
                // 属性上报：params → data, identifier 固定为 "report"
                message = getPropertyReport((PropertyReport) action);
                break;
            case REGISTER:
                // 设备注册：model + version → data
                message = getDeviceRegister((DeviceRegister) action);
                break;
            case STATE_CHANGE:
                // 状态变更：ONLINE→"online", OFFLINE→"offline"
                message = getStateChange((DeviceStateChange) action);
                break;
            case SERVICE_REPLY:
                // 服务回复：replyId→id, name+"_reply"→identifier
                message = getServiceReply((ServiceReply) action);
                break;
            case SUB_REGISTER:
                // 子设备注册：model + version + subs → data
                message = getSubDeviceRegister((SubDeviceRegister) action);
                break;
            default:
                throw exception(PARAMS_EXCEPTION);
        }

        // 【填充通用字段】
        // 消息 ID：优先使用 Action 的 id，没有则生成 UUID
        if (!StringUtils.hasText(action.getId())) {
            message.setId(IdUtil.fastSimpleUUID());
        }else {
            message.setId(action.getId());
        }
        // 时间戳：优先使用 Action 的 time，没有则取当前时间
        if (ObjectUtils.isEmpty(action.getTime())) {
            message.setTime(System.currentTimeMillis());
        }else {
            message.setTime(action.getTime());
        }
        // mid（消息 ID）与 id 相同，用于消息去重和追踪
        message.setMid(message.getId());
        // 设备标识
        message.setDn(action.getDeviceName());
        message.setProductKey(action.getProductKey());
        // occurred（发生时间）与 time 相同
        message.setOccurred(message.getTime());
        // 【关键】通过 DeviceApi 查询设备表，填充 deviceId
        // deviceId 是后续规则匹配、属性存储等操作的关键字段
        DeviceInfo deviceInfo = componentServices.getDeviceApi().getDeviceByPkDnByCache(action.getProductKey(), action.getDeviceName());
        if (deviceInfo == null || deviceInfo.getId() == null) {
            log.error("report: device not found, pk={}, dn={}", action.getProductKey(), action.getDeviceName());
            return;
        }
        message.setDeviceId(deviceInfo.getId());
        // 【关键】发布消息到 device_thing topic，进入规则引擎处理链
        sendMessage(message);
    }

    /**
     * 【事件上报 → ThingModelMessage 转换】
     * 输出：type="event", identifier=事件名, data=事件参数
     */
    private ThingModelMessage getEventReport(EventReport action) {
        return ThingModelMessage.builder()
                .data(action.getParams())
                .identifier(action.getName())
                .type(ThingModelMessage.TYPE_EVENT)
                .build();
    }

    /**
     * 【属性上报 → ThingModelMessage 转换】
     * 输出：type="property", identifier="report", data=属性键值对
     *
     * 这是最频繁的上报类型，设备通过此消息上报传感器数据。
     * 消息进入 device_thing topic 后，DevicePropertyHandler 会将属性值写入时序数据库。
     */
    private ThingModelMessage getPropertyReport(PropertyReport action) {
        return ThingModelMessage.builder()
                .data(action.getParams())
                .identifier(ThingModelMessage.ID_PROPERTY_REPORT)
                .type(ThingModelMessage.TYPE_PROPERTY)
                .build();
    }

    /**
     * 【设备注册 → ThingModelMessage 转换】
     * 输出：type="lifetime", identifier="register", data={model, version}
     */
    private ThingModelMessage getDeviceRegister(DeviceRegister action) {
        Map<String, Object> data = new HashMap<>();
        data.put("model", action.getModel());
        data.put("version", action.getVersion());
        return ThingModelMessage.builder()
                .data(data)
                .identifier(ThingModelMessage.ID_REGISTER)
                .type(ThingModelMessage.TYPE_LIFETIME)
                .build();
    }

    /**
     * 【子设备注册 → ThingModelMessage 转换】
     * 输出：type="lifetime", identifier="register", data={model, version, subs:[...]}
     */
    private ThingModelMessage getSubDeviceRegister(SubDeviceRegister action) {
        Map<String, Object> data = new HashMap<>();
        data.put("model", action.getModel());
        data.put("version", action.getVersion());

        List<Map<String, Object>> subs = new ArrayList<>();
        for (DeviceRegister sub : action.getSubs()) {
            Map<String, Object> subData = new HashMap<>();
            subData.put("model", sub.getModel());
            subData.put("version", sub.getVersion());
            subs.add(subData);
        }
        data.put("subs", subs);
        return ThingModelMessage.builder()
                .data(data)
                .identifier(ThingModelMessage.ID_REGISTER)
                .type(ThingModelMessage.TYPE_LIFETIME)
                .build();
    }

    /**
     * 【状态变更 → ThingModelMessage 转换】
     * 输出：type="state", identifier="online" 或 "offline"
     *
     * 设备上线/离线消息会被 DeviceStateCheckHandler 消费，
     * 更新 Redis 缓存和设备数据库状态。
     */
    private ThingModelMessage getStateChange(DeviceStateChange action) {
        String id = ThingModelMessage.ID_ONLINE;
        if (action.getState() == DeviceState.OFFLINE) {
            id = ThingModelMessage.ID_OFFLINE;
        }
        return ThingModelMessage.builder()
                .identifier(id)
                .type(ThingModelMessage.TYPE_STATE)
                .build();
    }

    /**
     * 【服务回复 → ThingModelMessage 转换】
     * 输出：type="service", identifier=服务名+"_reply", data=回复参数
     *
     * 通过 replyId 关联原始请求，identifier 后缀 "_reply" 标识这是回复消息。
     */
    private ThingModelMessage getServiceReply(ServiceReply action) {
        return ThingModelMessage.builder()
                .id(action.getReplyId())
                .data(action.getParams())
                .identifier(action.getName() + ThingModelMessage.SERVICE_REPLY_SUFFIX)
                .type(ThingModelMessage.TYPE_SERVICE)
                .build();
    }


}
