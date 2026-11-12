package com.enjoyiot.eiot.ruleengine.action.alert;

import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.action.Action;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 【告警动作】— 规则引擎 action type="alert"
 *
 * <h2>职责</h2>
 * 封装一组 AlertService 实例，遍历执行。
 * 一条规则可以关联多个告警配置（如同时发钉钉和邮件），
 * 每个告警配置对应一个 AlertService。
 *
 * <h2>创建</h2>
 * RuleManager.parseAction("alert", config) 创建：
 * <ol>
 *   <li>分页查询该规则关联的启用告警配置</li>
 *   <li>每个告警配置创建一个 AlertService</li>
 *   <li>封装为 AlertAction → 存入 Rule.actions</li>
 * </ol>
 *
 * <h2>执行</h2>
 * <pre>
 * execute(msg):
 *   for each AlertService:
 *     service.execute(msg)       // 执行 triggerScript + 发送通知
 *
 * recover(msg):
 *   for each AlertService:
 *     service.executeRecover(msg) // 执行 recoverScript + 发送恢复通知
 * </pre>
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class AlertAction implements Action<AlertService> {

    public static final String TYPE = "alert";

    private String type;

    /** 告警服务列表：每个 AlertConfig 对应一个 AlertService */
    private List<AlertService> services;

    @Override
    public String getType() {
        return TYPE;
    }

    /** 触发告警 — 遍历所有 AlertService 执行触发脚本 */
    @Override
    public List<String> execute(ThingModelMessage msg) {
        List<String> results = new ArrayList<>();
        for (AlertService service : services) {
            results.add(service.execute(msg));
        }
        return results;
    }

    /** 解除告警 — 遍历所有 AlertService 执行恢复脚本 */
    public List<String> recover(ThingModelMessage msg) {
        List<String> results = new ArrayList<>();
        for (AlertService service : services) {
            results.add(service.executeRecover(msg));
        }
        return results;
    }
}
