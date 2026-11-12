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
package com.enjoyiot.eiot.ruleengine.rule;


import com.enjoyiot.eiot.ruleengine.action.Action;
import com.enjoyiot.eiot.ruleengine.action.alert.AlertAction;
import com.enjoyiot.eiot.ruleengine.action.alert.AlertService;
import com.enjoyiot.eiot.ruleengine.action.device.DeviceAction;
import com.enjoyiot.eiot.ruleengine.action.device.DeviceActionService;
import com.enjoyiot.eiot.ruleengine.action.http.HttpAction;
import com.enjoyiot.eiot.ruleengine.action.http.HttpService;
import com.enjoyiot.eiot.ruleengine.action.kafka.KafkaAction;
import com.enjoyiot.eiot.ruleengine.action.kafka.KafkaService;
import com.enjoyiot.eiot.ruleengine.action.mqtt.MqttAction;
import com.enjoyiot.eiot.ruleengine.action.mqtt.MqttService;
import com.enjoyiot.eiot.ruleengine.action.tcp.TcpAction;
import com.enjoyiot.eiot.ruleengine.action.tcp.TcpService;
import com.enjoyiot.eiot.ruleengine.filter.DeviceFilter;
import com.enjoyiot.eiot.ruleengine.filter.Filter;
import com.enjoyiot.eiot.ruleengine.link.LinkFactory;
import com.enjoyiot.eiot.ruleengine.listener.DeviceCondition;
import com.enjoyiot.eiot.ruleengine.listener.DeviceListener;
import com.enjoyiot.eiot.ruleengine.listener.Listener;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.eiot.message.service.MessageService;
import com.enjoyiot.module.eiot.api.alert.AlertApi;
import com.enjoyiot.module.eiot.api.alert.dto.AlertConfig;
import com.enjoyiot.module.eiot.api.alert.dto.AlertConfigPageReqVO;
import com.enjoyiot.module.eiot.api.alert.dto.Message;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.rule.dto.FilterConfig;
import com.enjoyiot.module.eiot.api.rule.dto.RuleInfo;
import com.enjoyiot.module.eiot.api.task.dto.RuleAction;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【规则管理器】- 规则的注册、删除、解析、缓存
 *
 * 该类是规则引擎的管理中枢，负责：
 *  1. 规则解析：将数据库中的 RuleInfo（DTO）解析为 Rule（引擎可执行对象）
 *  2. 规则注册：将解析后的 Rule 注册到 RuleMessageHandler 的内存索引中
 *  3. 规则缓存：维护 ruleCache（ConcurrentHashMap），供延时任务回放使用
 *  4. 规则生命周期：add/remove/pause/resume
 *  5. 资源管理：规则删除时清理 Redis 缓存和 Link 连接
 *
 * 【规则解析流程（parseRule）】
 *  RuleInfo（数据库 DTO）
 *    → parseListener() → Listener（设备监听器）
 *    → parseFilter()   → Filter（设备过滤器）
 *    → parseAction()   → Action（动作：设备指令/HTTP/MQTT/Kafka/TCP/告警）
 *    → new Rule(...)
 *
 * 【动作类型支持（parseAction）】
 *  - device：设备指令下发（属性设置、服务调用）
 *  - http：HTTP 回调（调用第三方 API）
 *  - mqtt：MQTT 转发（转发消息到其他 MQTT Broker）
 *  - kafka：Kafka 推送（推送消息到 Kafka Topic）
 *  - tcp：TCP 推送（推送消息到 TCP 服务）
 *  - alert：告警通知（钉钉/邮件/短信等，通过 AlertApi 查询告警配置）
 *
 * 【告警动作特殊处理】
 *  AlertAction 解析时需要通过 AlertApi 分页查询该规则关联的所有告警配置，
 *  每个告警配置生成一个 AlertService 实例，包含脚本和通知消息模板。
 */
@Component
@Slf4j
public class RuleManager {

    /** 规则消息处理器（内存索引） */
    @Resource
    private RuleMessageHandler ruleMessageHandler;

    /** 设备动作服务（设备指令下发） */
    @Resource
    private DeviceActionService deviceActionService;

    /** 告警 API（查询告警配置） */
    @Resource
    private AlertApi alertApi;

    /** 设备 API（查询设备信息） */
    @Resource
    private DeviceApi deviceApi;

    /** 消息服务（发送告警通知） */
    @Resource
    private MessageService messageService;

    /** 触发控制服务（清理 Redis 缓存） */
    @Resource
    private TriggerControlService triggerControlService;

    /**
     * 【规则缓存】- 存储规则对象的本地缓存
     *
     * Key: 规则 ID
     * Value: 解析后的 Rule 对象
     *
     * 使用 ConcurrentHashMap 保证线程安全。
     * 用于延时任务回放时获取规则配置（延时队列只存储 ruleId，执行时需要从缓存取规则）。
     */
    private final Map<Long, Rule> ruleCache = new ConcurrentHashMap<>();

    /**
     * 【添加/更新规则】
     *
     * 处理逻辑：
     *  1. 解析 RuleInfo DTO 为 Rule 对象
     *  2. 如果是更新操作且规则已停止，清理 Redis 缓存
     *  3. 更新本地缓存（始终更新，确保最新配置）
     *  4. 只有运行中的规则才添加到 RuleMessageHandler 内存索引
     *
     * @param ruleInfo 规则信息（来自数据库）
     */
    public void add(RuleInfo ruleInfo) {
        // 第一步：将数据库 DTO 解析为引擎可执行的 Rule 对象
        Rule rule = parseRule(ruleInfo);
        // 第二步：如果本次是更新操作（缓存中已存在该 ruleId）
        // 且规则状态为"已停止"，则清理该规则在 Redis 中的遗留数据
        // （限流器状态、告警状态），避免脏数据残留
        boolean isUpdate = ruleCache.containsKey(ruleInfo.getId());
        if (isUpdate && RuleInfo.STATE_STOPPED.equals(ruleInfo.getState())) {
            triggerControlService.clearRuleCache(ruleInfo.getId());
        }
        // 第三步：始终更新本地缓存，保证下次通过 getRule() 获取到最新配置
        ruleCache.put(ruleInfo.getId(), rule);
        // 第四步：只有运行中的规则才注册到 RuleMessageHandler 内存索引，
        // 停止状态的规则不会参与消息匹配，实现"暂停"效果
        if (!RuleInfo.STATE_STOPPED.equals(ruleInfo.getState())) {
            ruleMessageHandler.putRule(rule);
        }
    }

    /**
     * 【删除规则】- 完整清理规则相关资源
     *
     * 1. 从内存索引中删除
     * 2. 从本地缓存中删除
     * 3. 关闭 Link 连接（MQTT/Kafka/TCP 等长连接）
     * 4. 清理 Redis 缓存（限流器、触发状态）
     */
    public void remove(Long ruleId) {
        // 1. 从 RuleMessageHandler 内存索引中移除，消息将不再匹配该规则
        ruleMessageHandler.removeRule(ruleId);
        // 2. 从本地缓存中移除
        ruleCache.remove(ruleId);
        // 3. 关闭规则关联的外部长连接（MQTT Broker、Kafka Producer、TCP Socket 等）
        LinkFactory.ruleClose(ruleId);
        // 4. 清理 Redis 中的限流器和告警状态，防止内存泄漏
        triggerControlService.clearRuleCache(ruleId);
    }

    /**
     * 暂停规则：从内存索引中移除，但保留本地缓存中的 Rule 对象。
     * 暂停后消息不再匹配该规则，但缓存中的数据仍可用于查询。
     * 恢复时调用 add() 会重新将规则注册到内存索引。
     */
    public void pause(Long ruleId) {
        remove(ruleId);
    }

    /**
     * 恢复规则：重新解析 RuleInfo 并注册到内存索引。
     * 因为 pause 时已清理 Redis 缓存，resume 会通过 add() 重新构建完整状态。
     */
    public void resume(RuleInfo ruleInfo) {
        add(ruleInfo);
    }

    /**
     * 获取规则缓存（供延时任务回放使用）
     */
    public Rule getRule(Long ruleId) {
        return ruleCache.get(ruleId);
    }

    /**
     * 【解析规则】- 将数据库 DTO 转换为引擎可执行对象
     *
     * 依次解析 Listener、Filter、Action，然后构造 Rule 对象。
     */
    private Rule parseRule(RuleInfo ruleInfo) {
        // 解析监听器列表
        List<Listener<?>> listeners = new ArrayList<>();
        for (FilterConfig listener : ruleInfo.getListeners()) {
            if (StringUtils.isBlank(listener.getConfig())) {
                continue;
            }
            listeners.add(parseListener(listener.getType(), listener.getConfig()));
        }

        // 解析过滤器列表
        List<Filter<?>> filters = new ArrayList<>();
        for (FilterConfig filter : ruleInfo.getFilters()) {
            if (StringUtils.isBlank(filter.getConfig())) {
                continue;
            }
            filters.add(parseFilter(filter.getType(), filter.getConfig()));
        }

        // 解析动作列表
        List<Action<?>> actions = new ArrayList<>();
        for (RuleAction action : ruleInfo.getActions()) {
            if (StringUtils.isBlank(action.getConfig())) {
                continue;
            }
            actions.add(parseAction(ruleInfo.getId(), action.getType(), action.getConfig()));
        }

        return new Rule(ruleInfo.getId(), ruleInfo.getName(), listeners, filters, actions, ruleInfo.getTenantId(), ruleInfo.getTriggerOptions());
    }

    /**
     * 【解析监听器】- 目前仅支持 DeviceListener（设备监听器）
     *
     * 为每个 DeviceCondition 设置 device 字段（格式：pk/dn 或 pk/#）
     */
    private Listener<?> parseListener(String type, String config) {
        if (DeviceListener.TYPE.equals(type)) {
            DeviceListener listener = parse(config, DeviceListener.class);
            for (DeviceCondition condition : listener.getConditions()) {
                String dn = "#";
                if (StringUtils.isNotBlank(listener.getDn())) {
                    dn = listener.getDn();
                }
                // device 格式："{pk}/#" 或 "{pk}/{dn}"
                condition.setDevice(listener.getPk() + "/" + dn);
            }
            return listener;
        }
        return null;
    }

    /**
     * 【解析过滤器】- 目前仅支持 DeviceFilter（设备过滤器）
     *
     * 注入 deviceApi，用于过滤器执行时查询设备当前属性值。
     */
    private Filter<?> parseFilter(String type, String config) {
        if (DeviceFilter.TYPE.equals(type)) {
            DeviceFilter filter = parse(config, DeviceFilter.class);
            for (com.enjoyiot.eiot.ruleengine.filter.DeviceCondition condition : filter.getConditions()) {
                String dn = "#";
                if (StringUtils.isNotBlank(filter.getDn())) {
                    dn = filter.getDn();
                }
                condition.setDevice(filter.getPk() + "/" + dn);
            }
            filter.setDeviceApi(deviceApi);
            return filter;
        }
        return null;
    }

    /**
     * 【解析动作】- 支持 6 种动作类型
     *
     * device: 设备指令下发
     * http:   HTTP 回调
     * mqtt:   MQTT 转发（需初始化 Link 连接）
     * kafka:  Kafka 推送（需初始化 Link 连接）
     * tcp:    TCP 推送（需初始化 Link 连接）
     * alert:  告警通知（分页查询告警配置，生成 AlertService 列表）
     */
    private Action<?> parseAction(Long ruleId, String type, String config) {
        if (DeviceAction.TYPE.equals(type)) {
            DeviceAction action = parse(config, DeviceAction.class);
            action.setDeviceActionService(deviceActionService);
            return action;
        } else if (HttpAction.TYPE.equals(type)) {
            HttpAction httpAction = parse(config, HttpAction.class);
            for (HttpService service : httpAction.getServices()) {
                service.setDeviceApi(deviceApi);
            }
            return httpAction;
        } else if (MqttAction.TYPE.equals(type)) {
            MqttAction mqttAction = parse(config, MqttAction.class);
            for (MqttService service : mqttAction.getServices()) {
                service.setDeviceApi(deviceApi);
                // 初始化 MQTT 连接
                service.initLink(ruleId);
            }
            return mqttAction;
        } else if (KafkaAction.TYPE.equals(type)) {
            KafkaAction kafkaAction = parse(config, KafkaAction.class);
            for (KafkaService service : kafkaAction.getServices()) {
                service.setDeviceApi(deviceApi);
                // 初始化 Kafka 连接
                service.initLink(ruleId);
            }
            return kafkaAction;
        } else if (TcpAction.TYPE.equals(type)) {
            TcpAction tcpAction = parse(config, TcpAction.class);
            for (TcpService service : tcpAction.getServices()) {
                service.setDeviceApi(deviceApi);
                // 初始化 TCP 连接
                service.initLink(ruleId);
            }
            return tcpAction;
        } else if (AlertAction.TYPE.equals(type)) {
            // 【告警动作特殊处理】
            // 一条规则可能关联多个告警配置（如同时通知钉钉和邮件）
            // 需要分页查询所有关联的告警配置，每个生成一个 AlertService
            AlertAction alertAction = parse(config, AlertAction.class);
            if (alertAction.getServices() == null || alertAction.getServices().isEmpty()) {
                log.warn("[parseAction] AlertAction services 为空，规则ID: {}", ruleId);
                return alertAction;
            }
            String script = alertAction.getServices().get(0).getScript();
            String recoverScript = alertAction.getServices().get(0).getRecoverScript();

            List<AlertService> alertServices = new ArrayList<>();

            Integer pageSize = 1000;
            for (int idx = 1; idx < 100; idx++) {
                AlertConfigPageReqVO pageReqVO = new AlertConfigPageReqVO();
                pageReqVO.setRuleInfoId(ruleId);
                pageReqVO.setPageNo(idx);
                pageReqVO.setPageSize(pageSize);

                PageResult<AlertConfig> alertConfigPage = alertApi.getAlertConfigPage(pageReqVO);

                for (AlertConfig alertConfig : alertConfigPage.getList()) {
                    // 只处理启用的告警配置
                    if (!alertConfig.isEnable()) {
                        continue;
                    }

                    AlertService service = new AlertService();
                    service.setScript(script);
                    service.setRecoverScript(recoverScript);
                    service.initDeviceApi(deviceApi);
                    service.setMessageService(messageService);

                    // 获取告警通知消息模板
                    Message message = alertApi.getNotifyMessage(alertConfig);
                    if (message == null) {
                        continue;
                    }

                    service.setMessage(message);
                    alertServices.add(service);
                }
                if (alertConfigPage.getTotal() < pageSize) {
                    break;
                }
            }
            alertAction.setServices(alertServices);
            return alertAction;
        }
        return null;
    }

    /**
     * JSON 反序列化工具方法
     */
    private <T> T parse(String config, Class<T> cls) {
        return JsonUtils.parseObject(config, cls);
    }

}
