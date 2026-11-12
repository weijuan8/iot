package com.enjoyiot.module.ai.dto.analysis;

import com.enjoyiot.module.ai.dal.dataobject.analysis.YFarmerHabitLibraryDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondFixedParamsDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondRuleLibraryDO;
import lombok.Data;

import java.util.List;

/**
 * AI 分析输入汇总实体
 *
 * 由 YPondDataCollector 根据塘口ID汇总各类数据后投喂给 AI：
 * 塘口基本信息 + 塘口画像（固定参数/规律/习惯） + 设备数据（近24小时时序） + 上报数据（昨日+今日）
 */
@Data
public class YPondAnalysisInput {

    // ========== 塘口基本信息 ==========

    /**
     * 塘口ID
     */
    private Long pondId;

    /**
     * 塘口名称
     */
    private String pondName;

    /**
     * 塘口编号
     */
    private String pondCode;

    /**
     * 养殖场ID
     */
    private Long farmId;

    /**
     * 养殖场名称
     */
    private String farmName;

    /**
     * 塘口类型（如 土塘/高位池/工厂化）
     */
    private String pondType;

    /**
     * 面积（平方米）
     */
    private String areaMu;

    /**
     * 塘口状态（如 在养/空塘/暂养）
     */
    private String status;

    // ========== 塘口画像（沉淀经验） ==========

    /**
     * 塘口固定参数（安全红线/基准参数）
     */
    private YPondFixedParamsDO fixedParams;

    /**
     * 塘口规律库（AI 复盘沉淀的可复用经验）
     */
    private List<YPondRuleLibraryDO> rules;

    /**
     * 农户习惯库（逐条总结农户养殖习惯）
     */
    private List<YFarmerHabitLibraryDO> habits;

    /**
     * 昨日建议摘要（用于复盘，可能为空）
     */
    private YesterdayAdvice yesterdayResult;

    // ========== 设备数据（近24小时时序） ==========

    /**
     * 塘口下所有设备数据
     */
    private List<YPondDeviceData> devices;

    // ========== 上报数据（昨日+今日） ==========

    /**
     * 近2天上报数据汇总文本（投喂/换水/浮头等）
     */
    private String reportSummary;

}
