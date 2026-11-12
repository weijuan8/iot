package com.enjoyiot.module.eiot.controller.admin.farm;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;
import com.enjoyiot.module.eiot.service.farm.YFarmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.List;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

/**
 * 养殖场管理 Controller
 *
 * @author EnjoyIot
 */
@Tag(name = "管理后台 - 养殖场管理")
@RestController
@RequestMapping("/eiot/farm")
@Validated
public class YFarmController {

    @Resource
    private YFarmService farmService;

    @PostMapping("/create")
    @Operation(summary = "创建养殖场",
            description = "已放开权限校验：所有登录管理员均可新增养殖场（原仅 super_admin 有 farm:farm:create 权限点）")
    public CommonResult<Long> createFarm(@Valid @RequestBody YFarmSaveReqVO createReqVO) {
        return success(farmService.createFarm(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新养殖场")
    @PreAuthorize("@ss.hasPermission('farm:farm:update')")
    public CommonResult<Boolean> updateFarm(@Valid @RequestBody YFarmSaveReqVO updateReqVO) {
        farmService.updateFarm(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除养殖场")
    @PreAuthorize("@ss.hasPermission('farm:farm:delete')")
    public CommonResult<Boolean> deleteFarm(@RequestParam("id") Long id) {
        farmService.deleteFarm(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取养殖场详情")
    @PreAuthorize("@ss.hasPermission('farm:farm:query')")
    public CommonResult<YFarmRespVO> getFarm(@RequestParam("id") Long id) {
        return success(farmService.getFarm(id));
    }

    @GetMapping("/page")
    @Operation(summary = "获取养殖场分页列表")
    @PreAuthorize("@ss.hasPermission('farm:farm:query')")
    public CommonResult<PageResult<YFarmRespVO>> getFarmPage(@Valid YFarmPageReqVO pageReqVO) {
        return success(farmService.getFarmPage(pageReqVO));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取养殖场简单列表（下拉选择用）")
    public CommonResult<List<YFarmSimpleRespVO>> getFarmSimpleList(@RequestParam(value = "tenantId", required = false) Long tenantId) {
        return success(farmService.getFarmSimpleList(tenantId));
    }
}
