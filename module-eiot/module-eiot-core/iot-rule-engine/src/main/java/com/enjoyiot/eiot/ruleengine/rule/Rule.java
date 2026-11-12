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
import com.enjoyiot.eiot.ruleengine.filter.Filter;
import com.enjoyiot.eiot.ruleengine.listener.Listener;
import com.enjoyiot.module.eiot.api.rule.dto.TriggerOptions;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 【规则实体】- 规则引擎的核心数据模型
 *
 * 一条完整的规则由三个核心组件组成：
 *
 * 【规则执行流水线】
 *  消息到达 → Listener(监听器) → Filter(过滤器) → Action(执行动作)
 *
 *  1. Listener（监听器）：
 *     - 决定哪些消息需要触发规则（如"产品A的所有设备"或"指定设备"）
 *     - 多个 Listener 之间是 OR 关系（任一匹配即触发）
 *     - 匹配维度：productKey + deviceName + messageType + identifier + 参数条件
 *
 *  2. Filter（过滤器）：
 *     - 对已触发的消息做进一步条件判断（如"温度 > 30°C"）
 *     - 多个 Filter 之间是 AND 关系（全部满足才通过）
 *     - 匹配维度：设备当前属性值、设备在线状态、设备标签值
 *
 *  3. Action（执行动作）：
 *     - 规则通过后执行的操作
 *     - 支持多种动作类型：设备指令下发、HTTP 回调、MQTT 转发、Kafka 推送、
 *       TCP 推送、告警通知等
 *     - 多个 Action 按顺序全部执行
 *
 *  4. TriggerOptions（触发控制）：
 *     - minIntervalSec：最小触发间隔（限频）
 *     - delaySec：延时执行（收到消息后延迟 N 秒再执行）
 *     - enableAlertRecover：是否启用告警解除
 *     - recoverQuietSec：告警解除的静默时长
 *
 * 【示例规则：温度过高告警】
 *  Listener: 产品A的设备#，type=property, identifier=report
 *  Filter:  设备属性 temperature > 30
 *  Action:  发送钉钉通知
 *  TriggerOptions: minIntervalSec=60（1分钟内不重复告警）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Rule {

    /** 规则唯一 ID */
    private Long id;

    /** 规则名称（用户可见） */
    private String name;

    /**
     * 监听器列表（OR 关系）
     * 决定哪些设备/消息需要进入规则匹配流程
     */
    private List<Listener<?>> listeners;

    /**
     * 过滤器列表（AND 关系）
     * 对已触发的消息进行条件判断，全部满足才执行动作
     */
    private List<Filter<?>> filters;

    /**
     * 动作列表（顺序执行）
     * 规则通过后执行的操作
     */
    private List<Action<?>> actions;

    /** 所属租户 ID（多租户隔离） */
    private Long tenantId;

    /**
     * 触发控制配置（频率限制、延时、告警解除）
     */
    private TriggerOptions triggerOptions;

}
