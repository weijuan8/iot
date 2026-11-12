package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 塘口 创建/更新 Request VO
 *
 * @author EnjoyIot
 */
@Data
public class YPondSaveReqVO {

    private Long id;

    @NotNull(message = "所属养殖场不能为空")
    private Long farmId;

    /**
     * 塘口编码（创建时后端自动生成，格式：POND-yyyyMMdd-5位随机数）
     */
    private String pondCode;

    @NotBlank(message = "塘口名称不能为空")
    private String pondName;

    @NotBlank(message = "塘口类型不能为空")
    private String pondType;

    @NotBlank(message = "状态不能为空")
    private String status;

    /** 面积（平方米） */
    @NotNull(message = "面积不能为空")
    private java.math.BigDecimal areaMu;

    private java.math.BigDecimal waterDepthAvgM;

    private java.math.BigDecimal waterDepthMaxM;

    private String bottomType;

    /** 基本信息总结（自由文本） */
    private String basicInfoSummary;

    /** 告警是否启用：是/否（前端传中文） */
    private String alertEnabled;

    /** 告警阈值 JSON 字符串 */
    private String alertThresholds;

    /** 告警模板ID列表 JSON 字符串，如 [1,2,3] */
    private String alertTemplateIds;

    /** AI 分析是否开启：默认开启；关闭后首页不返回 AI 数据，但每日分析仍执行 */
    private Boolean aiAnalysisEnabled;

    private String coverImageUrl;

    // 运维管理
    private String builtAt;
    private String lastCleanAt;
    private String lastDisinfectAt;
    private String lastMaintenanceAt;
    private String nextMaintenancePlan;
}
