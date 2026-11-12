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

import java.math.BigDecimal;

@Schema(description = "管理后台 - 数据分析属性配置 Resp VO")
@Data
public class YDataAnalysisPropertyVO {

    @Schema(description = "属性标识符（物模型 key）", example = "DO")
    private String key;

    @Schema(description = "属性中文名", example = "溶解氧")
    private String label;

    @Schema(description = "单位", example = "mg/L")
    private String unit;

    @Schema(description = "展示下限（阈值线）", example = "4")
    private BigDecimal min;

    @Schema(description = "展示上限（阈值线）", example = "12")
    private BigDecimal max;

    @Schema(description = "展示颜色", example = "#059669")
    private String color;

    @Schema(description = "图标")
    private String icon;

}
