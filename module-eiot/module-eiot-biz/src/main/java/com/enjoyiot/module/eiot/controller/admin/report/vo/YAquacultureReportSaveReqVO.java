package com.enjoyiot.module.eiot.controller.admin.report.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 种养上报 创建/更新 Request VO
 *
 * @author EnjoyIot
 */
@Data
public class YAquacultureReportSaveReqVO {

    private Long id;

    // ========== 层级关联 ==========

    @NotNull(message = "养殖场不能为空")
    private Long farmId;

    @NotNull(message = "塘口不能为空")
    private Long pondId;

    // ========== 操作信息 ==========

    /** 操作人ID */
    private Long operatorId;

    /** 操作人名称 */
    private String operatorName;

    /** 上报时间（yyyy-MM-dd HH:mm:ss，不传则默认当前时间） */
    private String reportTime;

    // ========== 投喂情况 ==========

    /** 是否投喂 */
    private Boolean feedingEnabled;

    /** 投喂量(kg) */
    private BigDecimal feedingAmountKg;

    /** 饲料类型 */
    private String feedingType;

    /** 摄食情况：good/normal/poor */
    private String feedingStatus;

    // ========== 今日操作 ==========

    /** 是否换水 */
    private Boolean waterChange;

    /** 换水比例 */
    private String waterRatio;

    /** 是否清底 */
    private Boolean bottomClean;

    /** 是否补菌 */
    private Boolean probiotics;

    /** 补菌产品名称 */
    private String probioticsName;

    /** 是否用药 */
    private Boolean medication;

    /** 药品名称 */
    private String medicationName;

    // ========== 水体观察 ==========

    /** 水色 */
    private String waterColor;

    /** 浮头情况：none/mild/severe */
    private String floatingHead;

    /** 死亡数量(条) */
    private Integer deathCount;

    /** 是否有病害 */
    private Boolean disease;

    /** 是否水质突变 */
    private Boolean waterQualityChange;

    // ========== 补充信息 ==========

    /** 备注 */
    private String remark;

    /** 现场照片（逗号分隔的URL） */
    private String images;
}
