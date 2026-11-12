package com.enjoyiot.eiot.ruleengine.rule;

import lombok.Data;

import java.io.Serializable;

/**
 * 【规则触发运行态】- 存储在 Redis 中的规则触发状态
 *
 * 用于跟踪规则的告警状态和恢复任务 Token，实现以下机制：
 *
 * 【告警状态管理】
 *  alerting=true  → 规则已被触发，当前处于告警中
 *  alerting=false → 规则未触发或已恢复
 *
 *  状态变化：
 *  - 规则匹配通过 → markTriggered() → alerting=true
 *  - 规则不再匹配 → scheduleRecoverIfNeeded() → 延时后执行恢复 → markRecovered() → alerting=false
 *  - 规则再次匹配 → cancelRecover() → 取消恢复任务 → 保持 alerting=true
 *
 * 【recoverToken 机制】
 *  用于防止"旧恢复任务误执行"的问题。
 *
 *  场景说明：
 *  1. 设备上报温度 35°C → 触发告警规则 → alerting=true
 *  2. 温度降至 25°C → 调度恢复任务（30 秒后执行），recoverToken="token_A"
 *  3. 恢复任务等待期间，温度又升至 40°C → 重新触发告警 → cancelRecover() 刷新 token → recoverToken="token_B"
 *  4. 30 秒后，"token_A"的恢复任务到期执行 → token 不匹配（当前是"token_B"）→ 跳过执行
 *
 *  这样确保了只有"最新"的恢复任务才会执行，避免了旧任务误恢复告警。
 *
 * 【存储方式】
 *  通过 Redisson RMap 存储在 Redis 中：
 *  Key: "rule:trigger:state"
 *  Field: 规则 ID
 *  Value: TriggerState 的 JSON 序列化
 */
@Data
public class TriggerState implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 是否处于告警中
     * true  → 规则已触发，当前告警中
     * false → 规则未触发或已恢复
     */
    private boolean alerting;

    /**
     * 待执行的恢复任务 Token（防止旧任务误恢复）
     *
     * 每次调度新的恢复任务时生成新的 Token，
     * 恢复任务执行时会校验 Token 是否匹配，
     * 不匹配说明该任务已过期（有更新的任务），跳过执行。
     */
    private String recoverToken;
}

