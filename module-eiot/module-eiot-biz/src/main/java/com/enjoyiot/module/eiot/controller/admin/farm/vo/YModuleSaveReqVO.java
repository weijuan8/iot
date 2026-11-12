package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 模块 创建/更新 Request VO
 *
 * @author EnjoyIot
 */
@Data
public class YModuleSaveReqVO {

    private Long id;

    @NotNull(message = "所属养殖场不能为空")
    private Long farmId;

    @NotNull(message = "所属塘口不能为空")
    private Long pondId;

    private String moduleCode;

    @NotBlank(message = "模块名称不能为空")
    private String moduleName;

    private String installPosition;

    private String installAt;

    private String installBy;

    private String powerSupplyType;
}
