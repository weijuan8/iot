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
 * 【设备配置下发】- 平台向设备下发配置参数（下行消息）
 *
 * 当平台需要更新设备配置时（如修改上报频率、阈值参数等），
 * 通过此消息将配置下发给设备。
 *
 * 【处理链路】
 *  1. 用户在管理后台修改设备配置
 *  2. biz 层构造 ThingModelMessage（type="config", identifier="set"）
 *  3. 消息发布到 device_thing topic
 *  4. AbstractComponent.handler() 通过 Redis 路由找到目标组件
 *  5. ThingComponent.onMessage() → doConfig() → deviceConfig()
 *  6. 具体组件（如 EmqxComponent）将配置通过 MQTT 下发给设备
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class DeviceConfig extends AbstractAction {

    /**
     * 配置模块名称
     * 用于区分不同模块的配置（如 "network"、"alarm"、"report"）
     */
    private String module;

    /**
     * 配置信息 Map
     * 例如 {"report_interval": 60, "alarm_threshold": 80}
     */
    private Map<String, Object> config;

    @Override
    public ActionType getType() {
        return ActionType.CONFIG;
    }
}
