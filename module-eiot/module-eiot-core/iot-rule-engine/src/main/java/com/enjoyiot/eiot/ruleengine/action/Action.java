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
package com.enjoyiot.eiot.ruleengine.action;



import com.enjoyiot.eiot.common.thing.ThingModelMessage;

import java.util.List;

/**
 * 【规则动作接口】- 规则引擎的第三道关卡（Action），规则通过后执行的最终操作
 *
 * Action 是规则匹配成功后实际执行的操作，支持多种类型：
 *  - device：设备指令下发（属性设置、服务调用）
 *  - http：HTTP 回调（调用第三方 API）
 *  - mqtt：MQTT 转发（转发消息到其他 MQTT Broker）
 *  - kafka：Kafka 推送（推送消息到 Kafka Topic）
 *  - tcp：TCP 推送（推送消息到 TCP 服务）
 *  - alert：告警通知（钉钉/邮件/短信等）
 *
 * 【Service 概念】
 *  一个 Action 可以包含多个 Service（服务实例）。
 *  例如 AlertAction 可能有多个 AlertService，分别对应不同的告警配置
 *  （如同时通知钉钉群 A 和钉钉群 B）。
 *
 * @param <T> Service 类型
 */
public interface Action<T> {

    /** 获取动作类型标识（如 "device"、"http"、"alert"） */
    String getType();

    /** 获取服务实例列表 */
    List<T> getServices();

    /**
     * 执行动作
     *
     * @param msg 物模型消息（包含设备信息、属性值等，供动作执行时使用）
     * @return 执行结果列表（每个 Service 返回一条结果描述）
     */
    List<String> execute(ThingModelMessage msg);
}
