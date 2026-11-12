package com.enjoyiot.module.eiot.controller.admin.farm;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO;
import com.enjoyiot.module.eiot.service.farm.YUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

/**
 * 单元(模块)聚合 Controller
 * 为小程序「单元」Tab 页提供数据
 *
 * @author EnjoyIot
 */
@Tag(name = "管理后台 - 单元详情")
@RestController
@RequestMapping("/eiot/unit")
public class YUnitController {

    @Resource
    private YUnitService yUnitService;

    @GetMapping("/detail")
    @Operation(summary = "获取单元详情（聚合：统计概览 + 设备控制 + 传感器实时数据 + 告警）")
    public CommonResult<YUnitDetailVO> getUnitDetail(
            @RequestParam("moduleId")
            @NotNull(message = "模块ID不能为空")
            @Parameter(description = "模块ID", required = true, example = "1")
            Long moduleId) {
        return success(yUnitService.getUnitDetail(moduleId));
    }
}
