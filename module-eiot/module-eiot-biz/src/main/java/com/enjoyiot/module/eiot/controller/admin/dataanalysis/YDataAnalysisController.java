package com.enjoyiot.module.eiot.controller.admin.dataanalysis;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisDeviceVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisExportReqVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisExportRespVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisSeriesRespVO;
import com.enjoyiot.module.eiot.service.dataanalysis.YDataAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

/**
 * 数据分析 Controller
 * 为小程序「数据分析」页提供 TDengine 时序聚合数据
 *
 * @author EnjoyIot
 */
@Tag(name = "管理后台 - 数据分析")
@RestController
@RequestMapping("/eiot/y-data-analysis")
public class YDataAnalysisController {

    @Resource
    private YDataAnalysisService yDataAnalysisService;

    @GetMapping("/properties")
    @Operation(summary = "获取模块下的设备及其可分析属性（设备 -> 属性，二级选择）")
    public CommonResult<List<YDataAnalysisDeviceVO>> yGetProperties(
            @RequestParam("moduleId")
            @NotNull(message = "模块ID不能为空")
            @Parameter(description = "模块ID", required = true, example = "1")
            Long moduleId) {
        return success(yDataAnalysisService.yGetProperties(moduleId));
    }

    @GetMapping("/series")
    @Operation(summary = "获取单台设备单个属性的时序数据（INTERVAL 降采样，SQL 层面聚合）")
    public CommonResult<YDataAnalysisSeriesRespVO> yGetSeries(
            @RequestParam("moduleId")
            @NotNull(message = "模块ID不能为空")
            @Parameter(description = "模块ID", required = true, example = "1")
            Long moduleId,
            @RequestParam("key")
            @NotNull(message = "属性标识符不能为空")
            @Parameter(description = "属性标识符（物模型 key）", required = true, example = "DO")
            String key,
            @RequestParam("deviceId")
            @NotNull(message = "设备编号不能为空")
            @Parameter(description = "设备编号（只查该设备该属性的趋势，不做多设备聚合）", required = true, example = "1001")
            Long deviceId,
            @RequestParam(value = "startTs", required = false)
            @Parameter(description = "开始时间戳（毫秒）", example = "1724572800000")
            Long startTs,
            @RequestParam(value = "endTs", required = false)
            @Parameter(description = "结束时间戳（毫秒）", example = "1724659200000")
            Long endTs,
            @RequestParam(value = "intervalMinutes", required = false)
            @Parameter(description = "降采样窗口（分钟），与 maxPoints 二选一", example = "10")
            Integer intervalMinutes,
            @RequestParam(value = "maxPoints", required = false)
            @Parameter(description = "目标返回点数，后端据此计算 INTERVAL 窗口，控制传输量", example = "60")
            Integer maxPoints) {
        return success(yDataAnalysisService.yGetSeries(moduleId, key, deviceId, startTs, endTs,
                intervalMinutes, maxPoints));
    }

    @PostMapping("/export")
    @Operation(summary = "导出数据（多设备多属性，按降采样窗口返回统一时间网格）",
            description = "与 /series 接口完全独立：/series 给图表渲染（单设备单属性 + X 轴标签），"
                    + "/export 给'导出 Excel'按钮专用。PC 与小程序共用。")
    public CommonResult<YDataAnalysisExportRespVO> yExport(
            @RequestBody @Valid YDataAnalysisExportReqVO req) {
        return success(yDataAnalysisService.yExport(req));
    }

}
