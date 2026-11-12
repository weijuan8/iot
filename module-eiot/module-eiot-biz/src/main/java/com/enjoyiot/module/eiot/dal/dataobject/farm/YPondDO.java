package com.enjoyiot.module.eiot.dal.dataobject.farm;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.framework.tenant.core.db.TenantBaseDO;
import lombok.*;

import java.time.LocalDate;

/**
 * 塘口 DO
 *
 * @author EnjoyIot
 */
@TableName("y_pond")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YPondDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 养殖场ID */
    private Long farmId;

    /** 塘口编码（业务唯一） */
    private String pondCode;

    /** 塘口名称 */
    private String pondName;

    /** 类型：高位池/土塘/工厂化/网箱/流水槽/稻田/其他 */
    private String pondType;

    /** 状态：空闲/在养/清塘/消毒/维修/废弃 */
    private String status;

    /** 面积（平方米） */
    private java.math.BigDecimal areaMu;

    /** 平均水深（米） */
    private java.math.BigDecimal waterDepthAvgM;

    /** 最大水深（米） */
    private java.math.BigDecimal waterDepthMaxM;

    /** 底质：土底/铺膜/沙底/水泥/砾石 */
    private String bottomType;

    /** 基本信息总结（TEXT，自由文本，塘口基本情况概述） */
    private String basicInfoSummary;

    // ========== 告警阈值（统一使用 alert_thresholds JSON 字段） ==========

    /** 告警是否启用：0-否 1-是 */
    private Boolean alertEnabled;

    /**
     * 告警阈值JSON。key=属性标识符，value={min,max,operator,value,name,unit,level}
     * 示例: {"NH":{"min":0,"max":0.5,"name":"氨氮","unit":"mg/L","level":"紧急"}}
     */
    private String alertThresholds;

    /**
     * 告警模板ID列表（JSON数组），关联 eiot_channel_template.id
     * 示例: [1, 2, 3]，支持多渠道同时通知（钉钉+短信+邮件）
     */
    private String alertTemplateIds;

    // ========== AI 分析 ==========

    /** AI 分析是否开启：默认开启；关闭后首页不返回 AI 数据，但每日分析仍执行 */
    private Boolean aiAnalysisEnabled;

    // ========== 照片影像 ==========

    /** 封面图URL */
    private String coverImageUrl;

    // ========== 运维管理 ==========

    /** 投产/建设日期 */
    private LocalDate builtAt;

    /** 上次清塘日期 */
    private LocalDate lastCleanAt;

    /** 上次消毒日期 */
    private LocalDate lastDisinfectAt;

    /** 上次设备维护日期 */
    private LocalDate lastMaintenanceAt;

    /** 下次维护计划日期 */
    private LocalDate nextMaintenancePlan;

    /** 租赁开始日期 */
    private LocalDate leaseStartAt;

    /** 租赁到期日期 */
    private LocalDate leaseEndAt;

    /** 每亩年租金（元） */
    private java.math.BigDecimal rentPerMu;

    /** 是否外包/承包：自养/承包/合作 */
    private String isLeased;
}
