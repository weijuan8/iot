/*
 *
 *  * | Licensed 未经许可不能去掉「EnjoyIot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2024] [EnjoyIot]
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

package com.enjoyiot.eiot.component.core.model.up;


import com.enjoyiot.eiot.component.core.model.ActionType;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * 【事件上报】- 设备上报告警或事件通知
 *
 * 与属性上报不同，事件上报表示设备发生了某个特定事件（通常需要被关注和处理）。
 * 属性上报侧重"状态"，事件上报侧重"发生了什么"。
 *
 * 【典型场景】
 *  - 温度过高告警：name="over_temp_alarm", params={"temperature": 85.0, "threshold": 80.0}
 *  - 门磁打开：name="door_open", params={"door_id": "main", "timestamp": 1234567890}
 *  - 设备故障：name="device_error", params={"error_code": 1001, "message": "传感器异常"}
 *
 * 【处理链路】
 *  与 PropertyReport 类似，但转换后的 ThingModelMessage.type = "event"，
 *  在规则引擎中会匹配事件类型的规则条件。
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class EventReport extends ReportAction {

    /**
     * 事件名称（对应物模型中定义的事件标识符）
     * 例如：over_temp_alarm、door_open、device_error
     */
    private String name;

    /**
     * 事件参数
     * 包含事件的详细数据，Key 为参数名，Value 为参数值
     */
    private Map<String, Object> params;

    @Override
    public ActionType getType() {
        return ActionType.EVENT_REPORT;
    }
}
