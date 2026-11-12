package com.enjoyiot.module.eiot.controller.admin.farm;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;
import com.enjoyiot.module.eiot.service.farm.YPondService;
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
 * 塘口管理 Controller
 *
 * @author EnjoyIot
 */
@Tag(name = "管理后台 - 塘口管理")
@RestController
@RequestMapping("/eiot/pond")
@Validated
public class YPondController {

    @Resource
    private YPondService pondService;

    @PostMapping("/create")
    @Operation(summary = "创建塘口",
            description = "已放开权限校验：所有登录管理员均可新增塘口（原仅 super_admin 有 farm:pond:create 权限点）")
    public CommonResult<Long> createPond(@Valid @RequestBody YPondSaveReqVO createReqVO) {
        return success(pondService.createPond(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新塘口")
    @PreAuthorize("@ss.hasPermission('farm:pond:update')")
    public CommonResult<Boolean> updatePond(@Valid @RequestBody YPondSaveReqVO updateReqVO) {
        pondService.updatePond(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-ai-enabled")
    @Operation(summary = "开启/关闭塘口 AI 分析（关闭后首页不返回 AI 数据，但每日分析仍执行）")
    @PreAuthorize("@ss.hasPermission('farm:pond:update')")
    public CommonResult<Boolean> updateAiAnalysisEnabled(@RequestParam("id") Long id,
                                                          @RequestParam("enabled") Boolean enabled) {
        pondService.updateAiAnalysisEnabled(id, enabled);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除塘口")
    @PreAuthorize("@ss.hasPermission('farm:pond:delete')")
    public CommonResult<Boolean> deletePond(@RequestParam("id") Long id) {
        pondService.deletePond(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取塘口详情")
    @PreAuthorize("@ss.hasPermission('farm:pond:query')")
    public CommonResult<YPondRespVO> getPond(@RequestParam("id") Long id) {
        return success(pondService.getPond(id));
    }

    @GetMapping("/page")
    @Operation(summary = "获取塘口分页列表")
    @PreAuthorize("@ss.hasPermission('farm:pond:query')")
    public CommonResult<PageResult<YPondRespVO>> getPondPage(@Valid YPondPageReqVO pageReqVO) {
        return success(pondService.getPondPage(pageReqVO));
    }

    @GetMapping("/properties")
    @Operation(summary = "获取塘口可配置属性（阈值配置候选）",
            description = "一条 SQL 由塘口下设备的产品物模型拼出，含告警源属性与联动目标开关，不含实时值与告警信息")
    @PreAuthorize("@ss.hasPermission('farm:pond:query')")
    public CommonResult<YPondPropertiesRespVO> getPondProperties(@RequestParam("pondId") Long pondId) {
        return success(pondService.getPondProperties(pondId));
    }

    @GetMapping("/list-by-farm")
    @Operation(summary = "按养殖场获取塘口列表（下拉选择用）")
    public CommonResult<List<YPondSimpleRespVO>> getPondListByFarmId(@RequestParam("farmId") Long farmId) {
        return success(pondService.getPondListByFarmId(farmId));
    }
}
