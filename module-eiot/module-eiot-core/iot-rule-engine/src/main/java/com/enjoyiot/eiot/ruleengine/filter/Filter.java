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
package com.enjoyiot.eiot.ruleengine.filter;


import com.enjoyiot.eiot.common.thing.ThingModelMessage;

import java.util.List;

/**
 * 【规则过滤器接口】- 规则引擎的第二道关卡（Filter）
 *
 * 过滤器在 Listener 匹配成功后执行，对消息和设备当前状态进行条件判断。
 * 多个 Filter 之间是 AND 关系（全部满足才通过）。
 *
 * 【与 Listener 的区别】
 *  - Listener：基于消息本身的元数据匹配（粗筛），决定"要不要处理"
 *  - Filter：  基于设备当前状态判断（精筛），决定"是否满足条件"
 *    - 例如：Filter 可以判断设备当前温度是否 > 30°C（需要查询设备当前属性值）
 *    - Listener 只能判断消息类型是否是属性上报
 *
 * 【实现类】
 *  - DeviceFilter：设备过滤器，按设备当前属性值/在线状态/标签值进行条件判断
 *
 * @param <T> 条件类型（DeviceFilter 使用 DeviceCondition）
 */
public interface Filter<T> {

    /** 获取过滤器类型标识（如 "device"） */
    String getType();

    /** 获取过滤条件列表（AND 关系，全部满足才通过） */
    List<T> getConditions();

    /** 初始化过滤器（如建立数据库连接、预加载数据等） */
    void init();

    /**
     * 执行过滤器匹配
     *
     * @param msg 物模型消息
     * @return true=全部条件满足, false=任一条件不满足
     */
    boolean execute(ThingModelMessage msg);
}
