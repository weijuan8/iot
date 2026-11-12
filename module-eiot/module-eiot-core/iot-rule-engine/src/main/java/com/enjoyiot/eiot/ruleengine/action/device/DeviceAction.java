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
package com.enjoyiot.eiot.ruleengine.action.device;

import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.action.Action;
import com.enjoyiot.eiot.ruleengine.action.device.DeviceActionService.Service;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 【设备动作】- 规则引擎 Action 实现，触发设备控制指令下发
 *
 * 当规则引擎匹配到某条规则后，除了执行告警通知等动作外，
 * 还可以通过 DeviceAction 向设备下发控制指令（属性设置/服务调用），
 * 实现"事件 → 规则 → 自动控制设备"的闭环联动。
 *
 * 【典型使用场景】
 *  ┌────────────────┬──────────────────────┬──────────────────────────────┐
 *  │ 触发事件        │ 规则条件              │ DeviceAction 动作             │
 *  ├────────────────┼──────────────────────┼──────────────────────────────┤
 *  │ 温度 > 40°C    │ 设备在厨房            │ 关闭燃气阀门（服务调用）       │
 *  │ 门磁打开        │ 夜间 23:00-06:00     │ 打开报警灯（属性设置）         │
 *  │ 湿度 < 30%     │ 设备在温室            │ 启动加湿器（属性设置）         │
 *  │ 烟雾传感器告警  │ 任意时间              │ 关闭所有电源（服务调用）       │
 *  └────────────────┴──────────────────────┴──────────────────────────────┘
 *
 * 【执行流程】
 *  1. RuleExecutor 调用 execute(ThingModelMessage msg)
 *  2. 遍历 services 列表，对每个 service 调用 deviceActionService.invoke()
 *  3. DeviceActionService 构造 ThingService 对象
 *  4. 调用 DeviceApi.invoke() → DeviceCtrlServiceImpl.invokeService()
 *  5. 进入标准下行消息链路
 *
 * 【与 DeviceActionExecutor 的区别】
 *  - DeviceAction（规则引擎）：实时响应设备事件，事件触发后立即执行
 *  - DeviceActionExecutor（定时任务）：按 Cron 表达式周期性执行设备操作
 *
 * @see DeviceActionService 设备动作服务
 * @see com.enjoyiot.eiot.ruleengine.RuleExecutor 规则执行器
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class DeviceAction implements Action<Service> {

    public static final String TYPE = "device";

    /** 动作类型标识 */
    private String type;

    /** 要执行的服务列表（一条规则可触发多个设备操作） */
    private List<Service> services;

    /** 设备动作服务（负责构造 ThingService 并调用 DeviceApi） */
    private DeviceActionService deviceActionService;

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 【执行设备动作】- 遍历 services 并逐一下发设备指令
     *
     * @param msg 触发规则的物模型消息（包含设备信息等上下文）
     * @return 执行结果列表（每个 service 的 JSON 序列化结果）
     */
    @Override
    public List<String> execute(ThingModelMessage msg) {
        List<String> results = new ArrayList<>();
        for (Service service : services) {
            deviceActionService.invoke(service);
            results.add(JsonUtils.toJsonString(service));
        }
        return results;
    }

}
