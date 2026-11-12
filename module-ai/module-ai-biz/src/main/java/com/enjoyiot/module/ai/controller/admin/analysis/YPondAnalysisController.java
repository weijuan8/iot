package com.enjoyiot.module.ai.controller.admin.analysis;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.module.ai.controller.admin.analysis.vo.YHomePageRespVO;
import com.enjoyiot.module.ai.controller.admin.analysis.vo.YPondAnalysisRespVO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YDailyAnalysisResultDO;
import com.enjoyiot.module.ai.service.analysis.YPondAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 塘口 AI 分析")
@RestController
@RequestMapping("/ai/analysis")
@Validated
public class YPondAnalysisController {

    @Resource
    private YPondAnalysisService pondAnalysisService;

    @GetMapping("/latest")
    @Operation(summary = "查询塘口最新一条 AI 分析结果（首页展示）")
    @Parameter(name = "pondId", description = "塘口ID", required = true)
    public CommonResult<YPondAnalysisRespVO> getLatest(@RequestParam("pondId") @NotNull(message = "塘口ID不能为空") Long pondId) {
        return success(pondAnalysisService.getLatestByPondId(pondId));
    }

    @GetMapping("/recent")
    @Operation(summary = "查询塘口最近 N 条 AI 分析结果")
    @Parameter(name = "pondId", description = "塘口ID", required = true)
    @Parameter(name = "count", description = "条数，默认10")
    public CommonResult<List<YDailyAnalysisResultDO>> getRecent(@RequestParam("pondId") @NotNull(message = "塘口ID不能为空") Long pondId,
                                                               @RequestParam(value = "count", required = false) Integer count) {
        return success(pondAnalysisService.getRecentList(pondId, count));
    }

    @GetMapping("/home-page")
    @Operation(summary = "首页数据（七模块拼接，供首页直接渲染）")
    @Parameter(name = "pondId", description = "塘口ID", required = true)
    public CommonResult<YHomePageRespVO> getHomePage(@RequestParam("pondId") @NotNull(message = "塘口ID不能为空") Long pondId) {
        return success(pondAnalysisService.getHomePage(pondId));
    }

    @PostMapping("/run")
    @Operation(summary = "手动触发塘口 AI 分析")
    @Parameter(name = "pondId", description = "塘口ID", required = true)
    public CommonResult<Long> run(@RequestParam("pondId") @NotNull(message = "塘口ID不能为空") Long pondId) {
        return success(pondAnalysisService.analyzePond(pondId).getId());
    }

    @PostMapping("/run-all")
    @Operation(summary = "手动触发全部塘口 AI 分析")
    public CommonResult<Integer> runAll() {
        return success(pondAnalysisService.analyzeAllPond());
    }

}
