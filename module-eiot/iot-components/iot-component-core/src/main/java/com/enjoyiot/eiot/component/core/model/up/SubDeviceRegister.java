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
 * 【子设备注册动作】- 网关上报告知其挂载的子设备列表
 *
 * 在 IoT 网关拓扑中，网关设备通过此消息向平台注册其下挂载的子设备。
 * 例如：一个 Zigbee 网关通过此消息注册其下所有 Zigbee 子设备（传感器、开关等）。
 *
 * 【网关拓扑概念】
 *  网关（Gateway）：直接连接平台的设备，负责代理子设备通信
 *  子设备（Sub-Device）：通过网关间接连接平台的设备，不直接与平台通信
 *
 * 【处理链路】
 *  1. 网关扫描发现子设备后，上报 SubDeviceRegister
 *  2. ThingComponent.report() 转换为 ThingModelMessage
 *     - data 包含 model、version 和 subs 列表
 *  3. biz 层处理：
 *     - 自动注册新的子设备
 *     - 更新网关-子设备拓扑关系
 *     - 为子设备初始化默认属性
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class SubDeviceRegister extends ReportAction {

    /**
     * 网关型号
     */
    private String model;

    /**
     * 网关固件版本号
     */
    private String version;

    /**
     * 子设备列表
     * 每个子设备包含 model（型号）和 version（版本）信息
     */
    private List<DeviceRegister> subs;

    @Override
    public ActionType getType() {
        return ActionType.SUB_REGISTER;
    }
}
