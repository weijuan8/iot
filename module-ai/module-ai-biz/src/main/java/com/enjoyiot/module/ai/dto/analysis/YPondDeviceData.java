package com.enjoyiot.module.ai.dto.analysis;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 塘口设备数据（投喂给 AI 的设备维度数据）
 */
@Data
public class YPondDeviceData {

    /**
     * 设备ID
     */
    private Long deviceId;

    /**
     * 设备名称
     */
    private String deviceName;

    /**
     * 设备DN
     */
    private String dn;

    /**
     * 产品Key
     */
    private String productKey;

    /**
     * 设备最新属性值（Map<属性标识符, 值>，仅包含已上传且在产品分析白名单 {@code POND_ANALYSIS_PRODUCT_PROPERTIES} 内的属性）
     */
    private Map<String, Object> lastProperties;

    /**
     * 近 24 小时属性 1 小时时间序列（连续量，投喂给 AI 分析趋势与缺氧时段）
     */
    private List<YPropertySeriesPoint> propertySeries;

    /**
     * 近 24 小时开关属性状态时间段（相邻相同状态已合并，如"02:00-04:00 开"）
     */
    private List<YPropertySwitchSegment> switchSegments;

    /**
     * 属性每日聚合统计（首页快照展示使用；投喂 AI 时由 {@code YPondAiPromptBuilder} 序列化前剔除，避免浪费 token）
     */
    private List<YPropertyDailyStat> dailyStats;

}
