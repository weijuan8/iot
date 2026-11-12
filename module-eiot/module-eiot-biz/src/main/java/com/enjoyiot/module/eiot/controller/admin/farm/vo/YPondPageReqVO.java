package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.enjoyiot.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 塘口 分页查询 Request VO
 *
 * @author EnjoyIot
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class YPondPageReqVO extends PageParam {

    /** 租户ID */
    private Long tenantId;

    private Long farmId;

    private String pondName;

    private String pondType;

    private String status;
}
