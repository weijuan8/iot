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
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 数据分析导出 Resp VO
 *
 * <p>返回结构对前端导出友好：headers 给表头，rows 按相同顺序给单元格值，
 * 前端可直接套用 Excel / CSV 模板生成下载文件，无需再做时间对齐。</p>
 */
@Schema(description = "管理后台 - 数据分析导出 Resp VO")
@Data
public class YDataAnalysisExportRespVO {

    @Schema(description = "表头列表（首列固定为「时间」，后续按请求 items 顺序拼接「设备名-属性名」）",
            example = "[\"时间\", \"设备A-溶解氧\", \"设备A-pH\"]")
    private List<String> headers;

    @Schema(description = "数据行：行内顺序与 headers 一致；空值用 \"\" 表示（前端转 Excel 时对应空单元格）")
    private List<List<Object>> rows;

    @Schema(description = "实际返回采样点数（= 时间网格点数）", example = "288")
    private Integer actualPoints;

    @Schema(description = "时间列格式（yyyy-MM-dd HH:mm）", example = "yyyy-MM-dd HH:mm")
    private String timeFormat;
}
