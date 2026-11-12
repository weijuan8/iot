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

package com.enjoyiot.eiot.ruleengine.handler;


import com.enjoyiot.eiot.common.thing.ThingModelMessage;

/**
 * 【设备消息处理器接口】- 规则引擎消费者链的处理器契约
 *
 * 所有设备消息处理器必须实现此接口，由 RuleDeviceConsumer 统一调度执行。
 * 实现类通过 @Order 注解控制执行顺序（Order 值越小越先执行）。
 *
 * 【执行顺序（按 @Order 排列）】
 *  1. DeviceMessageLogHandler    → 消息日志入库
 *  2. DevicePropertyHandler      → 物模型属性处理（校验 + 入库）
 *  3. DeviceStateCheckHandler    → 设备状态检查（在线/离线 + 子设备同步）
 *  4. DeviceConfigHandler        → 设备配置回复
 *  5. RuleMessageHandler         → 规则引擎匹配与执行
 *
 * 【实现方式】
 *  - 系统级 Handler（sys 包）：处理平台核心逻辑（日志、属性、状态、配置）
 *  - 规则引擎 Handler（rule 包）：处理用户定义的业务规则
 *
 * @see RuleDeviceConsumer 总调度器
 */
public interface  DeviceMessageHandler {

    /**
     * 处理设备消息
     *
     * @param message 物模型消息（来自设备上报或平台下发，通过 device_thing topic 传递）
     */
    void handle(ThingModelMessage message);

}
