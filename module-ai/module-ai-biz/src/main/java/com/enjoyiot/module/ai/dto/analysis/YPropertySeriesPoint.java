package com.enjoyiot.module.ai.dto.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 属性时间序列点（1 小时粒度）
 *
 * <p>设备上报的原始属性数据（1~5 分钟一次）按 1 小时窗口降采样为一个数据点
 * （value 取窗口内最近一次上报值），保留完整时间趋势供 AI 分析，
 * 如夜间溶氧下降、缺氧时段等。开关类属性不在此列（见 {@link YPropertySwitchSegment}）。</p>
 */
@Data
public class YPropertySeriesPoint {

    /**
     * 属性标识符（如 DO、pH、temp、NH）
     */
    private String propertyKey;

    /**
     * 窗口起始时间（1 小时粒度，格式 MM-dd HH:mm，如 08-22 03:00）
     */
    @JsonFormat(pattern = "MM-dd HH:mm")
    private LocalDateTime time;

    /**
     * 窗口代表值（该 1 小时内最近一次上报值）
     */
    private BigDecimal value;

}
