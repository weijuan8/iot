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
package com.enjoyiot.eiot.ruleengine.listener;


import com.enjoyiot.eiot.common.thing.ThingModelMessage;

import java.util.List;

/**
 * 【规则监听器接口】- 规则引擎的第一道关卡（Listener）
 *
 * 监听器决定"哪些消息需要触发规则"，是规则匹配的入口。
 * 多个 Listener 之间是 OR 关系（任一匹配即进入下一步）。
 *
 * 【与 Filter 的区别】
 *  - Listener：决定"要不要处理这条消息"（粗筛，基于消息本身的元数据）
 *  - Filter：  决定"消息内容是否满足条件"（精筛，基于设备当前状态）
 *
 * 【实现类】
 *  - DeviceListener：设备监听器，按 productKey + deviceName + messageType 匹配
 *
 * @param <T> 条件类型（DeviceListener 使用 DeviceCondition）
 */
public interface Listener<T> {

    /** 获取监听器类型标识（如 "device"） */
    String getType();

    /** 获取监听条件列表（OR 关系，任一条件匹配即通过） */
    List<T> getConditions();

    /**
     * 执行监听器匹配
     *
     * @param msg 物模型消息
     * @return true=匹配成功, false=不匹配
     */
    boolean execute(ThingModelMessage msg);
}
