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
package com.enjoyiot.module.eiot.enums;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 数据分析（TDengine 时序聚合）常量
 *
 * @author EnjoyIot
 */
public final class YDataAnalysisConstants {

    /**
     * 时间格式化模板（前端展示：MM/DD HH:mm）
     */
    public static final String TIME_PATTERN = "MM/dd HH:mm";

    /**
     * 可绘制趋势图的属性类型（连续量 + 开关量 0/1，排除 string/text 等）
     */
    public static final Set<String> NUMERIC_PROPERTY_TYPES = Set.of(
            "float", "double", "int32", "int64", "enum", "int", "long", "decimal", "bool");

    /**
     * 默认调色板（10 色）
     */
    public static final String[] COLOR_POOL = {
            "#059669", "#ef4444", "#10b981", "#f59e0b", "#0d9488",
            "#6366f1", "#8b5cf6", "#e11d48", "#0891b2", "#ca8a04"
    };

    /**
     * 属性展示配置（物模型属性标识符 → 展示配置），用于覆盖中文名/单位/阈值线/颜色
     */
    public static final Map<String, YPropDisplayConfig> PROP_DISPLAY_CONFIG = new LinkedHashMap<>();

    static {
        PROP_DISPLAY_CONFIG.put("DO", new YPropDisplayConfig("溶解氧", "mg/L", 4.0, 12.0, "#059669"));
        PROP_DISPLAY_CONFIG.put("PH", new YPropDisplayConfig("pH值", "", 6.5, 9.0, "#10b981"));
        PROP_DISPLAY_CONFIG.put("tem", new YPropDisplayConfig("水温", "°C", 20.0, 32.0, "#ef4444"));
        PROP_DISPLAY_CONFIG.put("NH", new YPropDisplayConfig("氨氮", "mg/L", 0.0, 0.5, "#f59e0b"));
        PROP_DISPLAY_CONFIG.put("do", new YPropDisplayConfig("溶解氧", "mg/L", 4.0, 12.0, "#059669"));
        PROP_DISPLAY_CONFIG.put("ph", new YPropDisplayConfig("pH值", "", 6.5, 9.0, "#10b981"));
        PROP_DISPLAY_CONFIG.put("temp", new YPropDisplayConfig("水温", "°C", 20.0, 32.0, "#ef4444"));
        PROP_DISPLAY_CONFIG.put("nh", new YPropDisplayConfig("氨氮", "mg/L", 0.0, 0.5, "#f59e0b"));
    }

    /**
     * 获取属性展示配置（忽略大小写）
     *
     * @param key 属性标识符
     * @return 展示配置，未配置返回 null
     */
    public static YPropDisplayConfig getPropDisplay(String key) {
        if (key == null) {
            return null;
        }
        YPropDisplayConfig config = PROP_DISPLAY_CONFIG.get(key);
        if (config != null) {
            return config;
        }
        return PROP_DISPLAY_CONFIG.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 按索引从调色板取色
     */
    public static String getColor(int index) {
        return COLOR_POOL[Math.abs(index) % COLOR_POOL.length];
    }

    /**
     * 属性展示配置
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class YPropDisplayConfig {

        /**
         * 中文名
         */
        private String label;

        /**
         * 单位
         */
        private String unit;

        /**
         * 展示下限（阈值线）
         */
        private Double min;

        /**
         * 展示上限（阈值线）
         */
        private Double max;

        /**
         * 展示颜色
         */
        private String color;

        public BigDecimal getMinDecimal() {
            return min == null ? null : BigDecimal.valueOf(min);
        }

        public BigDecimal getMaxDecimal() {
            return max == null ? null : BigDecimal.valueOf(max);
        }
    }
}
