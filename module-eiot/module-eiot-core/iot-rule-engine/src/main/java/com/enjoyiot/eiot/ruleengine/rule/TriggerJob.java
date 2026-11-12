package com.enjoyiot.eiot.ruleengine.rule;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 【延时队列任务载体】- Redisson 延时队列中传递的任务对象
 *
 * 当规则配置了延时执行（delaySec > 0）或告警恢复（enableAlertRecover=true）时，
 * 任务会被序列化后放入 Redisson RDelayedQueue，到期后由消费者线程取出执行。
 *
 * 【两种队列】
 *  1. actionQueue（动作延时队列）：
 *     - 触发时机：规则匹配通过后，delaySec > 0 时入队
 *     - 到期后执行：正常触发动作（recovery=false）
 *  2. recoverQueue（恢复延时队列）：
 *     - 触发时机：Listener/Filter 不匹配时，如果 enableAlertRecover=true 则入队
 *     - 到期后执行：告警恢复动作（recovery=true）
 *     - recoverToken 用于防止旧恢复任务误执行（新消息触发后 token 被刷新，旧任务 token 不匹配则跳过）
 *
 * 【序列化】
 *  实现 Serializable 接口，因为需要被 Redisson 序列化后存入 Redis。
 *  messageJson 存储 ThingModelMessage 的 JSON 序列化结果，避免直接序列化复杂对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerJob implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 规则 ID */
    private Long ruleId;

    /** 物模型消息的 JSON 字符串（反序列化后可还原为 ThingModelMessage） */
    private String messageJson;

    /** 是否为恢复任务（true=告警恢复, false=正常触发） */
    private boolean recovery;

    /** 恢复任务 Token（用于防止旧恢复任务误执行） */
    private String recoverToken;

    /** 租户 ID（执行时恢复租户上下文） */
    private Long tenantId;
}

