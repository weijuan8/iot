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

import java.util.List;

/**
 * 【属性获取】- 平台请求设备上报指定属性的当前值（下行消息）
 *
 * 当平台需要获取设备当前的最新属性值时（而非等待设备主动上报），
 * 通过此消息向设备发起属性读取请求。设备收到后应回复 PropertyReport。
 *
 * 【与属性上报的区别】
 *  - 属性上报（PropertyReport）：设备主动、周期性上报
 *  - 属性获取（PropertyGet）：平台主动请求、按需读取
 *
 * 【处理链路】
 *  1. 用户在管理后台点击"读取属性"或 API 调用
 *  2. biz 层构造 ThingModelMessage（type="property", identifier="get", data=[keys]）
 *  3. 消息经过组件路由下发给设备
 *  4. 设备读取传感器数据，通过 PropertyReport 上报当前值
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PropertyGet extends AbstractAction {

    /**
     * 需要获取的属性标识符列表
     * 例如 ["temperature", "humidity"] 表示读取温度和湿度
     */
    private List<String> keys;

    @Override
    public ActionType getType() {
        return ActionType.PROPERTY_GET;
    }
}
