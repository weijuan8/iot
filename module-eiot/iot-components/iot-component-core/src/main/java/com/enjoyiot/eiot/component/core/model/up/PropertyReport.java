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
 * 【属性上报】- 设备上报遥测数据（最重要的上行消息类型之一）
 *
 * 设备通过此消息将采集到的传感器数据上报给平台。
 *
 * 【典型数据格式】
 *  params = {
 *    "temperature": 25.5,    // 温度值
 *    "humidity": 60.0,       // 湿度值
 *    "power_switch": true    // 开关状态
 *  }
 *
 * 【完整处理链路】
 *  1. 设备通过 MQTT/HTTP 上报属性数据
 *  2. 协议组件（如 EmqxComponent）接收原始数据，构造 PropertyReport 对象
 *  3. ThingComponent.report(PropertyReport) 将其转换为 ThingModelMessage
 *     - type = "property"
 *     - identifier = "report"
 *     - data = params（属性 Map）
 *  4. sendMessage() 发布到消息队列 Topic: "device_thing"
 *  5. RuleDeviceConsumer 消费消息，依次触发：
 *     a. DeviceMessageLogHandler → 消息日志写入时序数据库（TDengine thing_model_message 表）
 *     b. DevicePropertyHandler → 属性值写入时序数据库（TDengine device_property 超级表）
 *                           → 更新 Redis 当前属性缓存（key: device:{deviceId}:properties）
 *     c. RuleMessageHandler → 匹配用户定义的规则（如温度 > 30°C 触发告警）
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class PropertyReport extends ReportAction {

    /**
     * 属性参数 Map
     * Key = 属性标识符（与物模型中定义的属性标识对应）
     * Value = 属性值（支持 String/Number/Boolean 等类型）
     */
    private Map<String, Object> params;

    @Override
    public ActionType getType() {
        return ActionType.PROPERTY_REPORT;
    }
}
