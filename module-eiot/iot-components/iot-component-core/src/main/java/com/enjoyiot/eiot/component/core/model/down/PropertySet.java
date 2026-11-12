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

package com.enjoyiot.eiot.component.core.model.down;



import com.enjoyiot.eiot.component.core.model.AbstractAction;
import com.enjoyiot.eiot.component.core.model.ActionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * 【属性设置】- 平台下发属性配置指令给设备（下行消息，最常用的控制指令）
 *
 * 当用户需要通过平台远程控制设备时（如开关灯、设置温度、调节亮度等），
 * 通过此消息将目标属性值下发给设备。
 *
 * 【典型场景】
 *  - 智能灯：{"power_switch": true}  开灯
 *  - 空调：{"target_temp": 26, "mode": "cool"}  设置制冷模式 26°C
 *  - 窗帘：{"position": 50}  窗帘开到 50%
 *
 * 【处理链路】
 *  1. 用户在 App/Web 控制设备
 *  2. biz 层构造 ThingModelMessage（type="property", identifier="set", data=params）
 *  3. 消息发布到 device_thing topic
 *  4. AbstractComponent.handler() 通过 Redis 查找设备所属组件路由
 *     - Redis Key: str:iot:device:router:{pk}:{dn}
 *     - Redis Value: {componentType}_{componentId}
 *  5. 匹配到目标组件后，调用 onMessage()
 *  6. ThingComponent.onMessage() → doProperty() → propertySet()
 *  7. 具体组件（如 EmqxComponent）通过 MQTT 下发给设备
 *  8. 设备执行指令，完成后可通过 PropertyReport 上报新状态
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PropertySet extends AbstractAction {

    /**
     * 属性参数 Map
     * Key = 属性标识符（如 "power_switch", "target_temp"）
     * Value = 属性目标值（支持泛型，String/Number/Boolean 等）
     */
    private Map<String, ?> params;

    @Override
    public ActionType getType() {
        return ActionType.PROPERTY_SET;
    }
}
