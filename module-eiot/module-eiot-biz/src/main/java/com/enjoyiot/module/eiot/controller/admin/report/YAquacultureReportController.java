package com.enjoyiot.module.eiot.controller.admin.report;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportPageReqVO;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportRespVO;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportSaveReqVO;
import com.enjoyiot.module.eiot.service.report.YAquacultureReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

/**
 * 种养上报（巡塘工作记录） Controller
 *
 * @author EnjoyIot
 */
@Tag(name = "管理后台 - 种养上报")
@RestController
@RequestMapping("/eiot/aquaculture-report")
@Validated
public class YAquacultureReportController {

    @Resource
    private YAquacultureReportService reportService;

    @PostMapping("/create")
    @Operation(summary = "创建上报记录")
//    @PreAuthorize("@ss.hasPermission('aquaculture:report:create')")
    public CommonResult<Long> createReport(@Valid @RequestBody YAquacultureReportSaveReqVO createReqVO) {
        return success(reportService.createReport(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新上报记录")
    @PreAuthorize("@ss.hasPermission('aquaculture:report:update')")
    public CommonResult<Boolean> updateReport(@Valid @RequestBody YAquacultureReportSaveReqVO updateReqVO) {
        reportService.updateReport(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除上报记录")
    @PreAuthorize("@ss.hasPermission('aquaculture:report:delete')")
    public CommonResult<Boolean> deleteReport(@RequestParam("id") Long id) {
        reportService.deleteReport(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取上报记录详情")
    @PreAuthorize("@ss.hasPermission('aquaculture:report:query')")
    public CommonResult<YAquacultureReportRespVO> getReport(@RequestParam("id") Long id) {
        return success(reportService.getReport(id));
    }

    @GetMapping("/page")
    @Operation(summary = "获取上报记录分页列表")
    @PreAuthorize("@ss.hasPermission('aquaculture:report:query')")
    public CommonResult<PageResult<YAquacultureReportRespVO>> getReportPage(@Valid YAquacultureReportPageReqVO pageReqVO) {
        return success(reportService.getReportPage(pageReqVO));
    }
}
