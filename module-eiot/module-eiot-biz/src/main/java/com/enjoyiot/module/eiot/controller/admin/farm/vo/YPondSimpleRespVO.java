package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 塘口 简单响应 VO（下拉列表用）
 *
 * @author EnjoyIot
 */
@Data
public class YPondSimpleRespVO {

    private Long id;

    private Long farmId;

    private String pondCode;

    private String pondName;

    private String status;
}
