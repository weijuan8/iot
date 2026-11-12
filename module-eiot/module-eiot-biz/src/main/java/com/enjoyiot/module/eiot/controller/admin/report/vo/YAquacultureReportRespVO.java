package com.enjoyiot.module.eiot.controller.admin.report.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 种养上报 响应 VO
 * 字段名匹配前端 ReportVO 接口结构
 *
 * @author EnjoyIot
 */
@Data
public class YAquacultureReportRespVO {

    private Long id;

    private Long tenantId;

    /** 租户名称（冗余，前端展示用） */
    private String tenantName;

    // ========== 层级关联（冗余名称，方便前端展示） ==========

    private Long farmId;

    private String farmName;

    private String farmCode;

    private Long pondId;

    private String pondName;

    // ========== 操作信息 ==========

    private Long operatorId;

    private String operatorName;

    private LocalDateTime reportTime;

    // ========== 投喂情况 ==========

    /** 是否投喂 */
    private Boolean feedingEnabled;

    /** 投喂信息（投喂时组装为对象，未投喂时为null） */
    private FeedingInfoVO feeding;

    // ========== 今日操作 ==========

    private OperationsVO operations;

    // ========== 水体观察 ==========

    private WaterObsVO waterObs;

    // ========== 补充信息 ==========

    /** 备注 */
    private String remark;

    /** 现场照片（逗号分隔的URL） */
    private String images;

    /** 创建时间 */
    private LocalDateTime createdAt;

    // ========== 内部子 VO ==========

    @Data
    public static class FeedingInfoVO {
        private BigDecimal amountKg;
        private String feedType;
        private String feedingStatus;
    }

    @Data
    public static class OperationsVO {
        private Boolean waterChange;
        private String waterRatio;
        private Boolean bottomClean;
        private Boolean probiotics;
        private String probioticsName;
        private Boolean medication;
        private String medicationName;
    }

    @Data
    public static class WaterObsVO {
        private String waterColor;
        private String floatingHead;
        private Integer deathCount;
        private Boolean disease;
        private Boolean waterQualityChange;
    }
}
