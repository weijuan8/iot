package com.enjoyiot.module.eiot.dal.dataobject.report;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.framework.tenant.core.db.TenantBaseDO;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 种养上报（巡塘工作记录） DO
 *
 * @author EnjoyIot
 */
@TableName("y_aquaculture_report")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YAquacultureReportDO extends TenantBaseDO {

    @TableId
    private Long id;

    // ========== 层级关联 ==========

    /** 养殖场ID */
    private Long farmId;

    /** 塘口ID */
    private Long pondId;

    // ========== 操作信息 ==========

    /** 操作人ID */
    private Long operatorId;

    /** 操作人名称 */
    private String operatorName;

    /** 上报时间 */
    private LocalDateTime reportTime;

    // ========== 投喂情况 ==========

    /** 是否投喂：0-否 1-是 */
    private Boolean feedingEnabled;

    /** 投喂量(kg) */
    private BigDecimal feedingAmountKg;

    /** 饲料类型 */
    private String feedingType;

    /** 摄食情况：good-好 normal-中 poor-差 */
    private String feedingStatus;

    // ========== 今日操作 ==========

    /** 是否换水：0-否 1-是 */
    private Boolean waterChange;

    /** 换水比例：1/4 1/3 1/2 full */
    private String waterRatio;

    /** 是否清底：0-否 1-是 */
    private Boolean bottomClean;

    /** 是否补菌：0-否 1-是 */
    private Boolean probiotics;

    /** 补菌产品名称 */
    private String probioticsName;

    /** 是否用药：0-否 1-是 */
    private Boolean medication;

    /** 药品名称 */
    private String medicationName;

    // ========== 水体观察 ==========

    /** 水色 */
    private String waterColor;

    /** 浮头情况：none-无 mild-轻微 severe-严重 */
    private String floatingHead;

    /** 死亡数量(条) */
    private Integer deathCount;

    /** 是否有病害：0-否 1-是 */
    private Boolean disease;

    /** 是否水质突变：0-否 1-是 */
    private Boolean waterQualityChange;

    // ========== 补充信息 ==========

    /** 备注 */
    private String remark;

    /** 现场照片（逗号分隔的URL） */
    private String images;
}
