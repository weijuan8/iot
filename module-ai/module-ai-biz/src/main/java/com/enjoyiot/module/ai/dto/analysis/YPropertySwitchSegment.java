package com.enjoyiot.module.ai.dto.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 开关属性状态时间段（连续相同状态合并为一个时间段）
 *
 * <p>开关量（值域 0/1，如增氧机开关）不按小时逐点投喂，
 * 而是把相邻且状态相同的小时窗口合并成一个时间段，
 * 大幅压缩数据量（如"一整天都是开"只需一个时间段），
 * 同时完整保留开关时段信息供 AI 分析氧机运行规律。</p>
 */
@Data
public class YPropertySwitchSegment {

    /**
     * 属性标识符（如 开关、oxygen_switch 等）
     */
    private String propertyKey;

    /**
     * 时间段起始（含），格式 MM-dd HH:mm，如 08-22 02:00
     */
    @JsonFormat(pattern = "MM-dd HH:mm")
    private LocalDateTime startTime;

    /**
     * 时间段结束（不含），格式 MM-dd HH:mm，如 08-22 04:00
     */
    @JsonFormat(pattern = "MM-dd HH:mm")
    private LocalDateTime endTime;

    /**
     * 该时间段内的状态：1-开 / 0-关
     */
    private BigDecimal value;

}
