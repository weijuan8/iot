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
import com.enjoyiot.eiot.common.enums.DeviceState;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

/**
 * 【设备在线状态变更】- 设备主动上报或平台检测到的上下线状态变化
 *
 * 当设备上线或离线时，通过此消息通知平台状态变更。
 * 状态变化可能由以下情况触发：
 *  - 设备主动上报：设备 MQTT 连接/断开时发送
 *  - 平台检测：心跳超时后由 DeviceStateCheckTask 自动生成
 *  - 网关通知：网关上报子设备的上/下线状态
 *
 * 【处理链路】
 *  1. ThingComponent.report() 转换为 ThingModelMessage
 *     - state=ONLINE  → identifier="online", type="state"
 *     - state=OFFLINE → identifier="offline", type="state"
 *  2. DeviceStateCheckHandler 处理：
 *     - 更新 Redis 中的设备在线状态缓存
 *     - 更新数据库中的设备状态字段
 *     - 如果是网关，同步更新其所有子设备状态
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class DeviceStateChange extends ReportAction implements Serializable {

    /**
     * 设备状态
     * ONLINE  - 设备已连接
     * OFFLINE - 设备已断开
     */
    private DeviceState state;

    @Override
    public ActionType getType() {
        return ActionType.STATE_CHANGE;
    }
}
