package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 养殖场 简单响应 VO（下拉列表用）
 *
 * @author EnjoyIot
 */
@Data
public class YFarmSimpleRespVO {

    private Long id;

    private Long tenantId;

    private String tenantName;

    private String farmCode;

    private String farmName;
}
