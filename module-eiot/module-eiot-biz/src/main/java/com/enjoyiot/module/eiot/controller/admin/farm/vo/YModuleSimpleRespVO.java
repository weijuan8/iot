package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 模块 简单响应 VO（下拉列表用）
 *
 * @author EnjoyIot
 */
@Data
public class YModuleSimpleRespVO {

    private Long id;

    private String moduleCode;

    private String moduleName;
}
