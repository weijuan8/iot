package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 模块 绑定/解绑设备 Request VO
 *
 * @author EnjoyIot
 */
@Data
public class YModuleBindDeviceReqVO {

    @NotNull(message = "模块ID不能为空")
    private Long moduleId;

    @NotNull(message = "设备ID不能为空")
    private Long deviceId;
}
