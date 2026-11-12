package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.enjoyiot.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模块 分页查询 Request VO
 *
 * @author EnjoyIot
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class YModulePageReqVO extends PageParam {

    /** 租户ID */
    private Long tenantId;

    private Long farmId;

    private Long pondId;

    private String moduleName;
}
