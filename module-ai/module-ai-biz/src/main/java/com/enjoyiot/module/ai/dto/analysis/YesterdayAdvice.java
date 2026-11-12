package com.enjoyiot.module.ai.dto.analysis;

import lombok.Data;

/**
 * 昨日建议轻量摘要（投喂给 AI 做复盘）
 *
 * 替代完整 {@code YDailyAnalysisResultDO} 投喂，只保留复盘判断真正需要的信息，
 * 避免把主表的系统字段（id/pondId/analysisDate/createTime/updater/deleted 等）泄入 prompt。
 */
@Data
public class YesterdayAdvice {

    /**
     * 昨日建议全文（AI 昨日输出的建议文本，用于判断农户是否执行）
     */
    private String adviceContent;

    /**
     * 昨日建议可信度（0-100，可选）
     */
    private Integer adviceCredibility;

}
