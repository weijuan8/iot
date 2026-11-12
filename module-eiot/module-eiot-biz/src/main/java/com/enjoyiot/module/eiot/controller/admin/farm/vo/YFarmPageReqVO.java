package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.enjoyiot.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 养殖场 分页查询 Request VO
 *
 * @author EnjoyIot
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class YFarmPageReqVO extends PageParam {

    /** 租户ID（平台管理员可按租户筛选） */
    private Long tenantId;

    private String farmName;

    private String farmType;
}
