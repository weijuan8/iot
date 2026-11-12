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
package com.enjoyiot.eiot.ruleengine.filter;


import com.enjoyiot.eiot.ruleengine.expression.Expression;
import com.enjoyiot.eiot.ruleengine.util.PathValueResolver;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 【过滤器条件】- 基于设备当前状态的条件判断
 *
 * 与 Listener 的 DeviceCondition 不同，这个类用于 Filter 阶段，
 * 它通过 DeviceApi 查询设备当前状态（属性值、在线状态、标签）来进行条件判断。
 *
 * 【支持的过滤类型】
 *  - property：查询设备当前属性值 → 与期望值比较
 *    - 从 Redis 缓存获取属性值
 *    - 支持嵌套路径（如 "data.temperature.inner"）
 *    - 支持多种比较运算符
 *  - state：判断设备在线状态（online == true/false）
 *  - tag：判断设备标签值（如 tag.location == "factory_a"）
 */
@Data
public class DeviceCondition {

    /** 目标设备（格式："pk/dn" 或设备 ID 数字字符串） */
    private String device;

    /** 过滤类型：property（属性）、state（状态）、tag（标签） */
    private String type;

    /** 属性标识符/标签名（property 类型时为属性标识，tag 类型时为标签名） */
    private String identifier;

    /** 期望值（用于比较） */
    private String value;

    /** 比较运算符（==、!=、>、<、>=、<=、between 等） */
    private String comparator;

    /** 设备 API（由 DeviceFilter 注入，用于查询设备状态） */
    private DeviceApi deviceApi;

    @Override
    public DeviceCondition clone() {
        DeviceCondition con = new DeviceCondition();
        con.setDevice(device);
        con.setType(type);
        con.setIdentifier(identifier);
        con.setValue(value);
        con.setComparator(comparator);
        con.setDeviceApi(deviceApi);
        return con;
    }

    /**
     * 【执行条件匹配】- 查询设备当前状态并与期望值比较
     *
     * 根据 type 不同，采取不同的查询策略：
     *
     * type="property" → 从 Redis 缓存查询设备属性值
     *   1. 通过 DeviceApi 获取设备信息
     *   2. 获取设备当前属性缓存（Map<属性标识, DevicePropertyCache>）
     *   3. 按 identifier 路径提取属性值
     *   4. 通过 Expression.eval() 比较
     *
     * type="state" → 判断设备在线状态
     *   直接使用 deviceInfo.isOnline() 的值进行比较
     *
     * type="tag" → 判断设备标签值
     *   从 deviceInfo.getTag() 中按 identifier 获取标签值进行比较
     */
    public boolean matches() {
        DeviceInfo deviceInfo;
        String[] pkDn = device.split("/");
        if (pkDn.length < 2) {
            // device 是数字字符串 → 按 deviceId 查询
            deviceInfo = deviceApi.getDeviceInfoFromCache(Long.parseLong(device));
        } else {
            // device 格式 "pk/dn" → 按 pk + dn 查询
            deviceInfo = deviceApi.getDeviceByPkDnByCache(pkDn[0], pkDn[1]);
        }
        if (deviceInfo == null) {
            return false;
        }

        List<Object> leftValues = Collections.emptyList();

        // === 属性值判断 ===
        if ("property".equals(type)) {
            // 从 Redis 缓存获取设备当前属性值
            Map<String, ?> properties = deviceApi.getPropertiesFromCache(deviceInfo.getId());
            if (properties == null || StringUtils.isBlank(identifier)) {
                return false;
            }
            // 提取根 key（如 "data.temperature.inner" → "data"）
            String rootKey = identifier.split("[.\\[]", 2)[0];
            DevicePropertyCache propertyCache = (DevicePropertyCache) properties.get(rootKey);
            if (propertyCache == null) {
                return false;
            }
            Object rootValue = propertyCache.getValue();
            if (rootValue == null) {
                return false;
            }
            // 如果 identifier 就是 rootKey，直接使用值
            // 否则通过路径解析器提取嵌套值
            if (identifier.equals(rootKey)) {
                leftValues = Collections.singletonList(rootValue);
            } else {
                leftValues = PathValueResolver.resolveValues(Collections.singletonMap(rootKey, rootValue), identifier);
            }

        // === 在线状态判断 ===
        } else if ("state".equals(type)) {
            leftValues = Collections.singletonList(deviceInfo.isOnline());

        // === 标签值判断 ===
        } else if ("tag".equals(type)) {
            Map<String, DeviceInfo.Tag> tags = deviceInfo.getTag();
            if (tags != null) {
                DeviceInfo.Tag tag = tags.get(identifier);
                if (tag != null) {
                    leftValues = Collections.singletonList(tag.getValue());
                }
            }
        }

        if (leftValues == null || leftValues.isEmpty()) {
            return false;
        }

        // 遍历提取到的值，通过 Expression.eval() 与期望值比较
        for (Object left : leftValues) {
            if (left == null) {
                continue;
            }
            // 复合类型 → JSON 序列化后比较
            if (left instanceof Map || left instanceof java.util.List || left.getClass().isArray()) {
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
