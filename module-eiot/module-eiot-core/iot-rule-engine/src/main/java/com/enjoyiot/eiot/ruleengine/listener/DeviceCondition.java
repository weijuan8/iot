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

import com.enjoyiot.eiot.ruleengine.expression.Expression;
import com.enjoyiot.eiot.ruleengine.util.PathValueResolver;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 【设备监听条件】- 定义设备监听器的匹配条件
 *
 * 每个 DeviceCondition 表示一条具体的匹配规则，包含：
 *  - device：目标设备（格式："pk/dn" 或 "pk/#"）
 *  - type：消息类型（property/event/service/state 等）
 *  - identifier：标识符（属性标识/事件名/服务名），支持 ":*" 后缀通配
 *  - parameters：参数条件列表（AND 关系，全部满足才匹配）
 *
 * 【通配符支持】
 *  - identifier = "temp:*" → 匹配以 "temp:" 开头的所有标识符（如 temp:high、temp:low）
 *  - parameter.identifier = "*" → 匹配任意参数
 *  - parameter.comparator = "*" → 参数存在即可，不比较值
 *
 * 【参数比较运算符（通过 Expression.eval() 实现）】
 *  ==, !=, >, <, >=, <=, between, notBetween, contain, notContain
 */
@Data
public class DeviceCondition {

    /** 消息类型（property/event/service/state 等） */
    private String type;

    /** 目标设备（格式："pk/dn" 或 "pk/#"） */
    private String device;

    /** 标识符（属性标识/事件名/服务名），支持 ":*" 后缀通配 */
    private String identifier;

    /** 参数条件列表（AND 关系，全部满足才通过） */
    private List<Parameter> parameters;

    /**
     * 【执行条件匹配】
     *
     * 匹配顺序：
     *  1. 检查消息类型是否一致
     *  2. 检查是否使用通配规则（identifier 以 ":*" 结尾）→ 直接通过
     *  3. 精确匹配 identifier
     *  4. 遍历所有 Parameter 条件（AND 关系）
     *
     * @param type       消息类型
     * @param identifier 消息标识符
     * @param parameter  消息数据（Map）
     * @return true=匹配, false=不匹配
     */
    public boolean matches(String type, String identifier, Map<?, ?> parameter) {
        // 【第1步】消息类型必须一致
        if (!this.type.equals(type)) {
            return false;
        }
        // 【第2步】通配规则：identifier 以 ":*" 结尾 → 无需判断其他条件，直接通过
        if (this.identifier.endsWith(":*")) {
            return true;
        }
        // 【第3步】精确匹配 identifier
        if (!this.identifier.equals(identifier)) {
            return false;
        }
        // 【第4步】遍历参数条件（AND 关系）
        for (Parameter p : this.parameters) {
            if (!p.matches(parameter)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 【条件参数】- 定义单个参数维度的匹配规则
     *
     * 通过 identifier 从消息数据中提取参数值，使用 comparator 与 value 比较。
     */
    @Data
    public static class Parameter {
        /** 参数标识符（从消息 data 中提取值的 key 或路径），"*" 表示匹配任意参数 */
        private String identifier;
        /** 期望值（用于比较） */
        private String value;
        /** 比较运算符（==、!=、>、<、>=、<=、between、notBetween、contain、notContain、"*"） */
        private String comparator;

        /**
         * 【参数匹配】
         *
         * @param parameter 消息的 data Map
         * @return true=匹配, false=不匹配
         */
        public boolean matches(Map<?, ?> parameter) {
            // 任意匹配：identifier 为 "*" → 直接通过
            if ("*".equals(identifier)) {
                return true;
            }

            // 参数存在即可，不比较值（comparator = "*"）
            if ((StringUtils.isBlank(identifier) || parameter.containsKey(identifier))
                    && "*".equals(comparator)) {
                return true;
            }

            // 通过路径解析器从参数 Map 中提取值（支持嵌套路径如 "data.temperature"）
            List<Object> values = StringUtils.isBlank(identifier)
                    ? Collections.emptyList()
                    : PathValueResolver.resolveValues(parameter, identifier);
            if (values.isEmpty()) {
                return false;
            }
            // 遍历提取到的值，任一匹配即通过
            for (Object left : values) {
                if (left == null) {
                    continue;
                }
                // 复合类型（Map/List/Array）→ JSON 序列化后比较
                if (left instanceof Map || left instanceof List || left.getClass().isArray()) {
                    if (Expression.eval(comparator, JsonUtils.toJsonString(left), value)) {
                        return true;
                    }
                    continue;
                }
                // 基本类型 → 字符串化后比较
                if (Expression.eval(comparator, String.valueOf(left), value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
