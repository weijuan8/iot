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
package com.enjoyiot.eiot.ruleengine.expression;


import com.enjoyiot.eiot.engine.IScriptEngine;
import com.enjoyiot.eiot.engine.ScriptEngineFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import static com.enjoyiot.eiot.common.enums.ErrorCodeConstants.DATA_BLANK;
import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 【表达式求值引擎】- 规则引擎中条件比较的核心工具类
 *
 * 提供统一的表达式比较能力，被 Listener 的 DeviceCondition.Parameter 和
 * Filter 的 DeviceCondition 共同使用。
 *
 * 【支持的比较运算符】
 *  ==          相等（字符串比较）
 *  !=          不等
 *  >           大于（数值比较，要求两个参数都是有效数字）
 *  <           小于（数值比较）
 *  >=          大于等于（数值比较）
 *  <=          小于等于（数值比较）
 *  between      在范围内（value 格式："最小值-最大值"，如 "10-30"）
 *  notBetween   不在范围内
 *  contain      包含（字符串包含判断）
 *  notContain   不包含
 *
 * 【参数说明】
 *  comparator：比较运算符（如 ">"）
 *  value：     实际值（设备上报的值或当前状态值）
 *  triggerValue：期望值（规则中配置的阈值，如 "30"）
 *
 * 例如：eval(">", "35", "30") → true（设备温度 35°C > 阈值 30°C）
 *
 * @author sjg
 */
@Slf4j
public class Expression {

    /**
     * 校验字符串是否为有效数字
     * 用于 > < >= <= 等数值比较前的校验
     */
    private static boolean isValidNumber(String str) {
        if (str == null || str.trim().isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 【执行表达式求值】
     *
     * @param comparator   比较运算符
     * @param value        实际值（设备上报/当前状态的值）
     * @param triggerValue 期望值（规则中配置的阈值）
     * @return 比较结果
     */
    /**
     * 【执行表达式求值】- 根据比较运算符对两个字符串值进行比较
     *
     * 调用链：Listener/Filter 的 DeviceCondition → 遍历条件 → Expression.eval()
     *
     * @param comparator   比较运算符（== != > < >= <= between notBetween contain notContain）
     * @param value        实际值（设备上报/当前状态的值，已被转换为字符串）
     * @param triggerValue 期望值（规则中配置的阈值，用户在前端设置的）
     * @return true=条件成立, false=条件不成立
     */
    public static boolean eval(String comparator, String value, String triggerValue) {
        boolean result = false;
        switch (comparator) {
            // === 字符串相等 ===
            // 例: eval("==", "online", "online") → true（设备状态匹配）
            case "==":
                result = value.equals(triggerValue);
                break;
            // === 字符串不等 ===
            case "!=":
                result = !value.equals(triggerValue);
                break;
            // === 数值大于 ===
            // 例: eval(">", "35", "30") → true（温度 35 > 阈值 30）
            // 注意：必须两个值都能解析为数字，否则返回 false（非数字不比较）
            case ">":
                if (!isValidNumber(value) || !isValidNumber(triggerValue)) {
                    log.warn("数值比较操作符 '>' 要求value和triggerValue都为有效数字，value: {}, triggerValue: {}，忽略此次比较", value, triggerValue);
                    result = false;
                } else {
                    result = Double.parseDouble(value) > Double.parseDouble(triggerValue);
                }
                break;
            // === 数值小于 ===
            case "<":
                if (!isValidNumber(value) || !isValidNumber(triggerValue)) {
                    log.warn("数值比较操作符 '<' 要求value和triggerValue都为有效数字，value: {}, triggerValue: {}，忽略此次比较", value, triggerValue);
                    result = false;
                } else {
                    result = Double.parseDouble(value) < Double.parseDouble(triggerValue);
                }
                break;
            // === 数值大于等于 ===
            case ">=":
                if (!isValidNumber(value) || !isValidNumber(triggerValue)) {
                    log.warn("数值比较操作符 '>=' 要求value和triggerValue都为有效数字，value: {}, triggerValue: {}，忽略此次比较", value, triggerValue);
                    result = false;
                } else {
                    result = Double.parseDouble(value) >= Double.parseDouble(triggerValue);
                }
                break;
            // === 数值小于等于 ===
            case "<=":
                if (!isValidNumber(value) || !isValidNumber(triggerValue)) {
                    log.warn("数值比较操作符 '<=' 要求value和triggerValue都为有效数字，value: {}, triggerValue: {}，忽略此次比较", value, triggerValue);
                    result = false;
                } else {
                    result = Double.parseDouble(value) <= Double.parseDouble(triggerValue);
                }
                break;
            // === 范围判断（在范围内）===
            // value 格式："最小值-最大值"（如 "20-40"），triggerValue 是设备实际值
            // 例: eval("between", "20-40", "35") → min=20, max=40, 35在[20,40]内 → true
            case "between":
                String[] valueRange = value.split("-");
                if (valueRange.length != 2) {
                    // value 格式错误（不是 "min-max" 格式）
                    log.warn("between操作符要求value格式为'最小值-最大值'，当前值: {}，忽略此次比较", value);
                    result = false;
                } else if (!isValidNumber(triggerValue) || !isValidNumber(valueRange[0]) || !isValidNumber(valueRange[1])) {
                    // value 或 triggerValue 不是数字
                    log.warn("between操作符要求triggerValue和value都为有效数字，triggerValue: {}, value: {}，忽略此次比较", triggerValue, value);
                    result = false;
                } else {
                    double min = Double.parseDouble(valueRange[0]); // 最小边界
                    double max = Double.parseDouble(valueRange[1]); // 最大边界
                    if (min > max) {
                        // 边界值不合法：最小值大于最大值
                        log.warn("between操作符要求最小值小于等于最大值，当前值: {}，忽略此次比较", value);
                        result = false;
                    } else {
                        // 核心判断：triggerValue 是否在 [min, max] 范围内（闭区间）
                        result = Double.parseDouble(triggerValue) >= min && Double.parseDouble(triggerValue) <= max;
                    }
                }
                break;
            // === 范围判断（不在范围内）===
            // 例: eval("notBetween", "20-40", "50") → 50 >= 40 → true（50 不在 [20,40] 内）
            case "notBetween":
                String[] valueRangeNot = value.split("-");
                if (valueRangeNot.length != 2) {
                    log.warn("notBetween操作符要求value格式为'最小值-最大值'，当前值: {}，忽略此次比较", value);
                    result = false;
                } else if (!isValidNumber(triggerValue) || !isValidNumber(valueRangeNot[0]) || !isValidNumber(valueRangeNot[1])) {
                    log.warn("notBetween操作符要求triggerValue和value都为有效数字，triggerValue: {}, value: {}，忽略此次比较", triggerValue, value);
                    result = false;
                } else {
                    double notMin = Double.parseDouble(valueRangeNot[0]);
                    double notMax = Double.parseDouble(valueRangeNot[1]);
                    if (notMin > notMax) {
                        log.warn("notBetween操作符要求最小值小于等于最大值，当前值: {}，忽略此次比较", value);
                        result = false;
                    } else {
                        // 核心判断：triggerValue < min 或 triggerValue > max
                        result = Double.parseDouble(triggerValue) < notMin || Double.parseDouble(triggerValue) > notMax;
                    }
                }
                break;
            // === 字符串包含 ===
            // 例: eval("contain", "pond1_temperature", "pond") → true
            case "contain":
                result = value.contains(triggerValue);
                break;
            // === 字符串不包含 ===
            case "notContain":
                result = !value.contains(triggerValue);
                break;
            // 未知操作符 → 默认 false（不匹配）
            default:
                break;
        }
        return result;
    }

}