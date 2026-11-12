package com.enjoyiot.eiot.ruleengine.action.alert;

import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.action.ScriptService;
import com.enjoyiot.eiot.message.service.MessageService;
import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.module.eiot.api.alert.dto.Message;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 【告警服务】— 规则引擎告警动作的执行单元
 *
 * <h2>职责</h2>
 * 一条告警配置（AlertConfig）对应一个 AlertService 实例。
 * 负责：
 * <ol>
 *   <li>执行用户编写的 JS 告警脚本，从消息中提取告警参数</li>
 *   <li>将脚本结果注入消息模板，发送通知（钉钉/邮件/短信/企微）</li>
 * </ol>
 *
 * <h2>创建时机</h2>
 * 规则启动注册时，RuleManager.parseAction("alert") 中：
 * <ol>
 *   <li>分页查询该规则关联的所有启用告警配置</li>
 *   <li>每个告警配置创建一个 AlertService 实例</li>
 *   <li>所有 AlertService 封装为 AlertAction 存入 Rule</li>
 * </ol>
 *
 * <h2>脚本执行</h2>
 * 用户在前端 AlertAction.vue 中编写 JS translate 函数：
 * <pre>
 * function translate(msg, device) {
 *     return {
 *         temperature: msg.data.temperature,   // 当前温度
 *         threshold: device.config.threshold,   // 阈值
 *         message: "温度过高: " + msg.data.temperature
 *     };
 * }
 * </pre>
 * 返回值中的 key 可在消息模板中通过 ${key} 引用。
 *
 * <h2>告警恢复</h2>
 * 支持可选的 recoverScript，触发告警解除时执行。
 * 若未配置 recoverScript，复用 triggerScript。
 * 恢复时注入 alertState="recover"，正常触发时注入 alertState="alert"。
 *
 * @see AlertAction 调用方
 * @see ScriptService 父类，提供 JS 脚本执行能力
 * @see MessageService 消息发送服务
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
public class AlertService extends ScriptService {

    /** 消息对象：含模板内容、渠道配置、参数占位符 */
    private Message message;

    /** 消息发送服务：发布 Spring Event → 各渠道 Listener 消费 */
    private MessageService messageService;

    /** 告警解除脚本，未配置则复用触发脚本 */
    private String recoverScript;

    /** 恢复脚本的独立执行器（与触发脚本引擎隔离） */
    private final ScriptService recoverExecutor = new ScriptService();

    /**
     * 注入 DeviceApi（给自身和恢复执行器）。
     * 脚本执行时需要 deviceApi 查询设备信息。
     */
    public void initDeviceApi(DeviceApi deviceApi) {
        super.setDeviceApi(deviceApi);
        recoverExecutor.setDeviceApi(deviceApi);
    }

    /**
     * 设置恢复脚本。
     * 如果提供了非空恢复脚本，初始化独立的恢复执行器。
     */
    public void setRecoverScript(String recoverScript) {
        this.recoverScript = recoverScript;
        if (StringUtils.isNotBlank(recoverScript)) {
            recoverExecutor.setScript(recoverScript);
        }
    }

    // ========================================================================
    // 公开入口
    // ========================================================================

    /** 触发告警 — 执行 triggerScript + 发送告警通知 */
    @SneakyThrows
    public String execute(ThingModelMessage msg) {
        return executeInternal(msg, false);
    }

    /** 解除告警 — 执行 recoverScript + 发送恢复通知 */
    public String executeRecover(ThingModelMessage msg) {
        return executeInternal(msg, true);
    }

    // ========================================================================
    // 内部实现
    // ========================================================================

    /**
     * 【统一执行入口】
     *
     * <p>三步走：
     * <ol>
     *   <li>执行 JS 脚本 → Map<String, Object> 结果</li>
     *   <li>注入 alertState 标记 ("alert" 或 "recover")</li>
     *   <li>绑定模板参数 → 发送通知消息</li>
     * </ol>
     *
     * @param msg     物模型消息（从设备上报）
     * @param recover true=恢复操作, false=正常告警
     */
    @SneakyThrows
    private String executeInternal(ThingModelMessage msg, boolean recover) {
        // === 步骤 1: 执行 JS 脚本 ===
        // translate(msg, deviceInfo) → {temperature:35, threshold:30, ...}
        Map<String, Object> result = executeScript(msg, recover);

        // === 步骤 2: 脚本结果校验 ===
        if (!validateScriptResult(result, recover)) {
            return "execScript result is null";
        }

        // === 步骤 3: 注入状态标记 ===
        // 模板中可通过 ${alertState} 判断是告警还是恢复
        enrichMessageResult(result, recover);

        // === 步骤 4: 发送通知 ===
        // 将脚本结果绑定到消息模板 → 发送 Spring Event → 各渠道 Listener 消费
        sendAlertMessage(result);

        return getResultMessage(recover);
    }

    /**
     * 执行脚本。
     * 恢复操作且有独立恢复脚本时用 recoverExecutor，否则用自身（triggerScript）。
     */
    private Map<String, Object> executeScript(ThingModelMessage msg, boolean recover) {
        if (recover && StringUtils.isNotBlank(recoverScript)) {
            return recoverExecutor.execScript(new TypeReference<Map<String, Object>>() {}, msg);
        } else {
            return execScript(new TypeReference<Map<String, Object>>() {}, msg);
        }
    }

    /**
     * 注入状态标记到脚本结果中。
     * <ul>
     *   <li>alertState="alert"   → 告警触发</li>
     *   <li>alertState="recover" → 告警解除</li>
     * </ul>
     * 使用 putIfAbsent：如果用户脚本已经设置了 alertState，不覆盖。
     */
    private void enrichMessageResult(Map<String, Object> result, boolean recover) {
        result.putIfAbsent("alertState", recover ? "recover" : "alert");
    }

    /**
     * 发送告警通知。
     * 将脚本结果中的 key-value 绑定到消息模板的参数中，
     * 通过 MessageService 发布 Spring Event，由各渠道 Listener 消费。
     */
    private void sendAlertMessage(Map<String, Object> result) {
        message.setParam(result);                           // 绑定模板参数
        message.setTenantId(TenantContextHolder.getTenantId());
        messageService.sendMessage(message);               // 发布 MessageEvent
    }

    private boolean validateScriptResult(Map<String, Object> result, boolean recover) {
        if (result == null) {
            log.warn("execScript result is null, recover={}", recover);
            return false;
        }
        return true;
    }

    private String getResultMessage(boolean recover) {
        return recover ? "recover_ok" : "ok";
    }
}
