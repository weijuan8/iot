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

/**
 * 【设备注册动作】- 设备首次连接时向平台注册自身信息
 *
 * 设备上线后需要向平台注册自己的型号和固件版本，
 * 平台据此判断是否需要下发配置或触发 OTA 升级。
 *
 * 【处理链路】
 *  1. 设备通过认证后，上报 DeviceRegister（model + version）
 *  2. ThingComponent.report() 将其转换为 ThingModelMessage（type="lifetime", identifier="register"）
 *  3. 消息进入 device_thing topic
 *  4. RuleDeviceConsumer 触发 RuleMessageHandler 规则匹配：
 *     - 如果设备固件版本低于最新版本 → 自动触发 OTA 升级流程
 *     - 如果设备首次注册 → 初始化设备属性默认值
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class DeviceRegister extends ReportAction {

    /**
     * 设备型号（Model）
     * 用于标识设备的硬件型号，与物模型定义关联
     */
    private String model;

    /**
     * 固件版本号
     * 用于判断是否需要 OTA 升级
     */
    private String version;

    @Override
    public ActionType getType() {
        return ActionType.REGISTER;
    }
}
