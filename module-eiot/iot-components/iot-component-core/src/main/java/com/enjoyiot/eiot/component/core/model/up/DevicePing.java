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
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * 【设备心跳 Ping】- 设备定期发送心跳包保持在线状态
 *
 * IoT 设备通过定时发送 PING 消息向平台表明自己仍在运行。
 * 平台通过保活机制判断设备是否在线：
 *  - 如果在超时时间内收到 PING → 设备在线
 *  - 如果超过保活时长未收到 PING → 判定设备离线
 *
 * 【处理链路】
 *  1. 设备定时发送 PING（通常间隔 30~60 秒）
 *  2. ThingComponent.report() 转换为 ThingModelMessage
 *  3. DeviceStateCheckHandler 处理：
 *     - 更新 Redis 中设备的最后通讯时间
 *     - 对于之前已离线的设备，自动发送上线状态消息
 *  4. DeviceStateCheckTask 定时任务（每 10 秒）扫描所有设备：
 *     - 对比最后通讯时间与保活时长
 *     - 超时则自动发送 TYPE_STATE + ID_OFFLINE 消息
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class DevicePing extends ReportAction {

    @Override
    public ActionType getType() {
        return ActionType.PING;
    }
}
