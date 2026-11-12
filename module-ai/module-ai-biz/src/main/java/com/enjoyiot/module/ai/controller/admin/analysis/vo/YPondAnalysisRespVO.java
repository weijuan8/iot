package com.enjoyiot.module.ai.controller.admin.analysis.vo;

import com.enjoyiot.module.ai.dal.dataobject.analysis.YFarmerHabitLibraryDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondFixedParamsDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondRuleLibraryDO;
import com.enjoyiot.module.ai.dto.analysis.YAiOutput;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 塘口 AI 分析结果（前端首页展示）
 *
 * 由 y_daily_analysis_result 主表（含 analysis_output JSON）+ 塘口画像 组装而成；
 * AI 输出明细字段（riskSummary/feedingAdvice/...）由主表 analysis_output JSON 解析填充。
 */
@Data
public class YPondAnalysisRespVO {

    // ========== 主表信息（y_daily_analysis_result） ==========

    /**
     * 主表ID
     */
    private Long id;

    /**
     * 塘口ID
     */
    private Long pondId;

    /**
     * 塘口名称
     */
    private String pondName;

    /**
     * 分析日期
     */
    private LocalDate analysisDate;

    /**
     * AI 建议全文
     */
    private String adviceContent;

    /**
     * 建议可信度（0-100）
     */
    private Integer adviceCredibility;

    /**
     * AI 当日分析输出全文（JSON 字符串，按输出格式组织，前端可自行解析展示）
     */
    private String analysisOutput;

    /**
     * 是否已复盘
     */
    private Boolean isReviewed;

    /**
     * 复盘日期
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

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    // ========== AI 输出明细（主表 analysis_output JSON 解析，结构化展示） ==========

    /**
     * 昨日复盘
     */
    private YAiOutput.Review review;

    /**
     * 首页输出（safety/aiConclusions/feed/oxy/wq/review，对应 docs/home-page-api.md）
     */
    private YAiOutput.HomePage homePage;

    /**
     * 画像更新
     */
    private YAiOutput.PortraitUpdate portraitUpdate;

    // ========== 塘口画像（沉淀经验，首页展示） ==========

    /**
     * 塘口固定参数
     */
    private YPondFixedParamsDO fixedParams;

    /**
     * 塘口规律列表
     */
    private List<YPondRuleLibraryDO> rules;

    /**
     * 农户习惯列表
     */
    private List<YFarmerHabitLibraryDO> habits;

}
