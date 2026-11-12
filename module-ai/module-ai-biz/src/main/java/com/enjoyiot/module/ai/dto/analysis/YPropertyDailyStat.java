package com.enjoyiot.module.ai.dto.analysis;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 属性每日聚合统计
 *
 * 用于将设备属性历史数据聚合为每天的 最小值/最大值/平均值/最新值，控制投喂给 AI 的数据量
 */
@Data
public class YPropertyDailyStat {

    /**
     * 设备ID
     */
    private Long deviceId;

    /**
     * 设备名称
     */
    private String deviceName;

    /**
     * 属性标识符（如 DO、pH、temp、NH）
     */
    private String propertyKey;

    /**
     * 属性中文名（如 溶氧、水温）
     */
    private String propertyName;

    /**
     * 属性单位（如 mg/L、℃）
     */
    private String unit;

    /**
     * 日期
     */
    private LocalDate date;

    /**
     * 当日最小值
     */
    private BigDecimal min;

    /**
     * 当日最大值
     */
    private BigDecimal max;

    /**
     * 当日平均值
     */
    private BigDecimal avg;

    /**
     * 当日最新值
     */
    private BigDecimal latest;

    /**
     * 当日上报次数
     */
    private Integer count;

    /**
     * 当日数值之和（仅聚合过程使用，不序列化）
     */
    @JsonIgnore
    private BigDecimal sum;

}
