/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot]
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
package com.enjoyiot.eiot.ruleengine.listener;

import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 【设备监听器】- 按设备维度匹配消息
 *
 * 这是规则引擎中最核心的监听器实现，通过以下维度进行消息匹配：
 *
 * 【匹配维度】
 *  1. productKey（产品 Key）：必须匹配
 *  2. deviceName（设备名称）：支持通配符 "#"（匹配该产品下所有设备）
 *  3. messageType（消息类型）：property/event/service/state 等
 *  4. identifier（标识符）：属性标识/事件名/服务名，支持后缀通配符 ":*"
 *  5. 参数条件：通过 DeviceCondition.Parameter 进行值匹配（==、!=、>、< 等）
 *
 * 【通配符规则】
 *  - dn="#"  → 匹配该产品下所有设备
 *  - dn="dev_001" → 只匹配指定设备
 *  - identifier="temp:*" → 匹配以 "temp:" 开头的所有标识符
 *  - identifier="over_temp_alarm" → 精确匹配
 *
 * 【示例】
 *  Listener { pk="prod_001", dn="#", type="property", identifier="report" }
 *  匹配产品 prod_001 下所有设备上报的属性消息。
 */
@Slf4j
@Data
public class DeviceListener implements Listener<DeviceCondition> {

    /** 监听器类型常量 */
    public static final String TYPE = "device";

    /** 类型标识（固定为 "device"） */
    private String type;

    /** 产品 Key（ProductKey） */
    private String pk;

    /** 设备名称（DeviceName），"#" 表示所有设备 */
    private String dn;

    /** 条件列表（OR 关系，任一条件匹配即通过） */
    private List<DeviceCondition> conditions;

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 【执行监听器匹配】
     *
     * 匹配流程：
     *  1. 遍历所有 DeviceCondition
     *  2. 检查产品 Key 是否匹配
     *  3. 检查设备名称是否匹配（支持 "#" 通配）
     *  4. 调用 condition.matches() 检查消息类型、标识符和参数条件
     *  5. 任一条件匹配成功 → 返回 true
     */
    @Override
    public boolean execute(ThingModelMessage message) {
        String identifier = message.getIdentifier();
        Map<String, Object> mapData = message.dataToMap();
        String pk = message.getProductKey();
        String dn = message.getDn();
        for (DeviceCondition condition : this.conditions) {
            // 解析 condition 的 device 字段（格式："pk/dn"）
            String condPkDn = condition.getDevice();
            String[] pkAndDn = condPkDn.split("/");
            String condPk = pkAndDn[0];
            String condDn = pkAndDn[1];

            // 【第1层】判断产品是否匹配
            if (!pk.equals(condPk)) {
                continue;
            }
            // 【第2层】判断设备是否匹配（"#" = 匹配所有设备）
            if (!"#".equals(condDn) && !dn.equals(condDn)) {
                continue;
            }

            // 【第3层】判断消息类型、标识符和参数条件
            if (condition.matches(message.getType(), identifier, mapData)) {
                return true;
            }
        }

        return false;
    }

}
