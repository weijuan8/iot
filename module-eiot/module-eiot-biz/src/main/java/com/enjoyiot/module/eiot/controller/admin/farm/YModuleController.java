package com.enjoyiot.module.eiot.controller.admin.farm;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;
import com.enjoyiot.module.eiot.dal.dataobject.deviceinfo.EiotDeviceInfoDO;
import com.enjoyiot.module.eiot.service.farm.YModuleService;
import com.enjoyiot.module.system.api.tenant.TenantApi;
import com.enjoyiot.module.system.api.tenant.dto.TenantSimpleRespDTO;
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
 * 模块管理 Controller
 *
 * @author EnjoyIot
 */
@Tag(name = "管理后台 - 模块管理")
@RestController
@RequestMapping("/eiot/module")
@Validated
public class YModuleController {

    @Resource
    private YModuleService moduleService;

    @Resource
    private TenantApi tenantApi;

    @GetMapping("/tenant-list")
    @Operation(summary = "获取租户列表（下拉选择用，无需权限）")
    public CommonResult<List<TenantSimpleRespDTO>> getTenantList() {
        return success(tenantApi.getTenantList());
    }

    @PostMapping("/create")
    @Operation(summary = "创建模块",
            description = "已放开权限校验：所有登录管理员均可新增模块（原仅 super_admin 有 farm:module:create 权限点）")
    public CommonResult<Long> createModule(@Valid @RequestBody YModuleSaveReqVO createReqVO) {
        return success(moduleService.createModule(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新模块")
    @PreAuthorize("@ss.hasPermission('farm:module:update')")
    public CommonResult<Boolean> updateModule(@Valid @RequestBody YModuleSaveReqVO updateReqVO) {
        moduleService.updateModule(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除模块")
    @PreAuthorize("@ss.hasPermission('farm:module:delete')")
    public CommonResult<Boolean> deleteModule(@RequestParam("id") Long id) {
        moduleService.deleteModule(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取模块详情")
    @PreAuthorize("@ss.hasPermission('farm:module:query')")
    public CommonResult<YModuleRespVO> getModule(@RequestParam("id") Long id) {
        return success(moduleService.getModule(id));
    }

    @GetMapping("/page")
    @Operation(summary = "获取模块分页列表")
    @PreAuthorize("@ss.hasPermission('farm:module:query')")
    public CommonResult<PageResult<YModuleRespVO>> getModulePage(@Valid YModulePageReqVO pageReqVO) {
        return success(moduleService.getModulePage(pageReqVO));
    }

    @PostMapping("/bind-device")
    @Operation(summary = "绑定设备到模块")
    @PreAuthorize("@ss.hasPermission('farm:module:update')")
    public CommonResult<Boolean> bindDevice(@Valid @RequestBody YModuleBindDeviceReqVO reqVO) {
        moduleService.bindDevice(reqVO.getModuleId(), reqVO.getDeviceId());
        return success(true);
    }

    @PostMapping("/unbind-device")
    @Operation(summary = "解绑设备")
    @PreAuthorize("@ss.hasPermission('farm:module:update')")
    public CommonResult<Boolean> unbindDevice(@Valid @RequestBody YModuleBindDeviceReqVO reqVO) {
        moduleService.unbindDevice(reqVO.getModuleId(), reqVO.getDeviceId());
        return success(true);
    }

    @GetMapping("/devices")
    @Operation(summary = "获取模块下已绑定的设备列表")
    @PreAuthorize("@ss.hasPermission('farm:module:query')")
    public CommonResult<List<YModuleDeviceRespVO>> getModuleDevices(@RequestParam("moduleId") Long moduleId) {
        return success(moduleService.getModuleDevices(moduleId));
    }

    @GetMapping("/list-by-pond")
    @Operation(summary = "按塘口获取模块列表（下拉选择用）")
    public CommonResult<List<YModuleSimpleRespVO>> getModuleListByPondId(@RequestParam("pondId") Long pondId) {
        return success(moduleService.getModuleListByPondId(pondId));
    }

    @GetMapping("/unbound-devices")
    @Operation(summary = "获取未被任何模块绑定的设备列表")
    @PreAuthorize("@ss.hasPermission('farm:module:query')")
    public CommonResult<List<EiotDeviceInfoDO>> getUnboundDeviceList() {
        return success(moduleService.getUnboundDeviceList());
    }
}
