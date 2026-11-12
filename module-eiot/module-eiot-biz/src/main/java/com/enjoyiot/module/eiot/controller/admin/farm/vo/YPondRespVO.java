package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 塘口 响应 VO（详情/列表）
 * 字段名匹配前端 PondVO 接口
 *
 * @author EnjoyIot
 */
@Data
public class YPondRespVO {

    private Long id;

    private Long tenantId;

    /** 租户名称（冗余，前端展示用） */
    private String tenantName;

    private Long farmId;

    /** 养殖场名称（冗余，前端展示用） */
    private String farmName;

    private String pondCode;

    private String pondName;

    private String pondType;

    private String status;

    /** 面积（平方米） */
    private java.math.BigDecimal areaMu;

    private java.math.BigDecimal waterDepthAvgM;

    private java.math.BigDecimal waterDepthMaxM;

    private String bottomType;

    /** 基本信息总结（自由文本，塘口基本情况概述） */
    private String basicInfoSummary;

    /** 告警是否启用：是/否 */
    private String alertEnabled;

    /** 告警阈值 JSON 字符串 */
    private String alertThresholds;

    /** 告警模板ID列表 JSON 字符串 */
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

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
}
