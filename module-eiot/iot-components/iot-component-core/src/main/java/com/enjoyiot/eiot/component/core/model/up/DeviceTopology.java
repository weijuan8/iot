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

import java.util.List;

/**
 * 【设备拓扑更新】- 网关上报其子设备拓扑关系变化
 *
 * 与 SubDeviceRegister 类似，但拓扑更新侧重于"关系变化"而非"注册新设备"。
 * 当网关的子设备发生增减或关系变化时上报此消息。
 *
 * 【与 SubDeviceRegister 的区别】
 *  - SubDeviceRegister：侧重设备注册，包含网关自身的 model/version
 *  - DeviceTopology：侧重拓扑关系，仅包含子设备列表
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class DeviceTopology extends ReportAction {

    /**
     * 当前网关下的子设备列表
     * 每个子设备包含 model 和 version 信息
     */
    private List<DeviceRegister> subs;

    @Override
    public ActionType getType() {
        return ActionType.TOPOLOGY;
    }
}
