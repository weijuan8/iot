/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot]
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */
package com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 数据分析导出 Req VO
 *
 * <p>与 /series 接口完全独立：/series 给图表渲染用（单设备单属性 + X 轴标签），
 * /export 给"导出 Excel / CSV"按钮专用（多设备多属性 + 统一时间网格）。</p>
 *
 * <p>PC 端导出按钮调用此接口；小程序端如有导出需求也可复用。</p>
 */
@Schema(description = "管理后台 - 数据分析导出 Req VO")
@Data
public class YDataAnalysisExportReqVO {

    @Schema(description = "模块编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "模块ID不能为空")
    private Long moduleId;

    @Schema(description = "开始时间戳（毫秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1724572800000")
    @NotNull(message = "开始时间戳不能为空")
    private Long startTs;

    @Schema(description = "结束时间戳（毫秒，必须大于开始时间）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1724659200000")
    @NotNull(message = "结束时间戳不能为空")
    @Min(value = 1, message = "结束时间戳不能为空")
    private Long endTs;

    @Schema(description = "降采样窗口（分钟）。选得越小采样点越密；若设备实际数据稀疏，"
            + "桶内可能无数据，对应单元格为空。", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    @NotNull(message = "降采样间隔不能为空")
    @Min(value = 1, message = "降采样间隔必须 ≥ 1 分钟")
    private Integer intervalMinutes;

    @Schema(description = "导出项列表（多设备多属性）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "至少选择一个导出项")
    @Valid
    private List<Item> items;

    @Schema(description = "单个导出项（设备 + 属性）")
    @Data
    public static class Item {
        @Schema(description = "设备编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1001")
        @NotNull(message = "设备编号不能为空")
        private Long deviceId;

        @Schema(description = "属性标识符（物模型 key）", requiredMode = Schema.RequiredMode.REQUIRED, example = "DO")
        @NotNull(message = "属性标识符不能为空")
        private String key;

        @Schema(description = "设备名称（仅用于导出表头，可选）", example = "设备A")
        private String deviceName;

        @Schema(description = "属性中文名（仅用于导出表头，可选）", example = "溶解氧")
        private String label;
    }
}
