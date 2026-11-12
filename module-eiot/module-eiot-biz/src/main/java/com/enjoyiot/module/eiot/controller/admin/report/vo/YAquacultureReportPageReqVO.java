package com.enjoyiot.module.eiot.controller.admin.report.vo;

import com.enjoyiot.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 种养上报 分页查询 Request VO
 *
 * @author EnjoyIot
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class YAquacultureReportPageReqVO extends PageParam {

    /** 租户ID */
    private Long tenantId;

    /** 养殖场ID */
    private Long farmId;

    /** 塘口ID */
    private Long pondId;

    /** 操作人名称（模糊搜索） */
    private String operatorName;

    /** 上报时间范围-开始（格式：yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd） */
    private String reportTimeStart;

    /** 上报时间范围-结束 */
    private String reportTimeEnd;
}
