package com.enjoyiot.module.ai.dal.dataobject.analysis;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.module.ai.dal.dataobject.YAiBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDate;

/**
 * 每日分析结果主表 DO（每天06:00分析，次日复盘回填）
 *
 * 表：y_daily_analysis_result
 *
 * 说明：AI 当日分析输出的结构化数据（风险总结/投喂建议/溶氧建议/水质建议/建议列表/复盘/画像更新）
 * 统一以 JSON 字符串存放于 {@link #analysisOutput} 字段，前端查询时解析展示；
 * 不单独建输出明细表。
 */
@TableName("y_daily_analysis_result")
@KeySequence("y_daily_analysis_result_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class YDailyAnalysisResultDO extends YAiBaseDO {

    /**
     * 塘口ID
     */
    private Long pondId;

    // ========== 【第一天·AI分析时填写（06:00）】 ==========

    /**
     * 分析日期（每天06:00对应日期）
     */
    private LocalDate analysisDate;

    /**
     * AI建议内容（围绕溶氧/投喂/水质等的当天建议全文）
     */
    private String adviceContent;

    /**
     * 建议可信度（0-100）
     */
    private Integer adviceCredibility;

    /**
     * AI当日分析输出全文（JSON字符串：riskSummary/feedingAdvice/oxygenAdvice/waterAdvice/adviceList/review/portraitUpdate）
     */
    private String analysisOutput;

    // ========== 【第二天·复盘时回填】 ==========

    /**
     * 是否已复盘（默认否，次日回填是）
     */
    private Boolean isReviewed;

    /**
     * 复盘日期（次日对昨日分析结果复盘）
     */
    private LocalDate reviewDate;

    /**
     * 农户是否执行建议（是/否/部分执行）
     */
    private String userExecuted;

    /**
     * 执行是否有效（优/良/一般/差/待观察）
     */
    private String executeEffective;

    /**
     * 执行情况描述
     */
    private String executeDiscribe;

    /**
     * 复盘结论
     */
    private String reviewConclusion;

}
