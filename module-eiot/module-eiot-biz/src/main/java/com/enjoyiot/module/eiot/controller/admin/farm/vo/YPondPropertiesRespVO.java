package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 塘口可配置属性响应 VO
 *
 * <p>用于阈值配置时提供「可用参数」候选，直接由塘口下设备的产品物模型拼出，
 * 不含实时值、告警与状态等聚合信息。
 *
 * @author EnjoyIot
 */
@Data
public class YPondPropertiesRespVO {

    @Schema(description = "可作为告警源的属性（非 bool 开关）")
    private List<PropertyOption> properties;

    @Schema(description = "可作为联动目标的开关属性（accessMode=rw 且 bool）")
    private List<PropertyOption> controls;

    @Data
    @Schema(description = "物模型属性可选项")
    public static class PropertyOption {

        @Schema(description = "属性标识符", example = "DO")
        private String identifier;

        @Schema(description = "属性名称", example = "溶氧")
        private String name;

        @Schema(description = "单位", example = "mg/L")
        private String unit;

        @Schema(description = "数据类型", example = "float")
        private String dataType;

        @Schema(description = "读写模式", example = "r")
        private String accessMode;
    }
}
