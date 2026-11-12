package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.enjoyiot.framework.common.util.json.databind.TimestampLongSerializer;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 单元(模块)详情 VO
 * 为小程序「单元」Tab 页提供聚合数据
 *
 * @author EnjoyIot
 */
@Schema(description = "管理后台 - 单元详情 Response VO")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YUnitDetailVO {

    @Schema(description = "统计概览")
    private YUnitStats stats;

    @Schema(description = "设备列表（每个设备含其传感器参数与控制项）")
    private List<YUnitDevice> devices;

    @Schema(description = "告警记录列表")
    private List<YUnitAlert> alerts;

    // ==================== 内部类 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "统计概览")
    public static class YUnitStats {
        @Schema(description = "传感器总数", example = "6")
        private int sensorCount;
        @Schema(description = "告警总数", example = "2")
        private int alarmCount;
        @Schema(description = "开启的控制数", example = "1")
        private int controlOnCount;
        @Schema(description = "控制总数", example = "3")
        private int controlTotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "设备聚合信息（含该设备的传感器参数与控制项）")
    public static class YUnitDevice {
        @Schema(description = "设备ID")
        private Long deviceId;
        @Schema(description = "设备名称")
        private String deviceName;
        @Schema(description = "产品Key")
        private String productKey;
        @Schema(description = "产品名称")
        private String productName;
        @Schema(description = "是否在线")
        private Boolean online;
        @Schema(description = "该设备的传感器参数列表")
        private List<SensorItem> sensors;
        @Schema(description = "该设备的控制项列表（布尔类型的读写参数）")
        private List<ControlItem> controls;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @Schema(description = "传感器项")
        public static class SensorItem {
            @Schema(description = "属性标识符")
            private String identifier;
            @Schema(description = "属性名称")
            private String name;
            @Schema(description = "当前值")
            private Object value;
            @Schema(description = "单位")
            private String unit;
            @Schema(description = "数据类型")
            private String dataType;
            @Schema(description = "访问模式")
            private String accessMode;
            @Schema(description = "最小值（数值类型物模型约束）")
            private Object min;
            @Schema(description = "最大值（数值类型物模型约束）")
            private Object max;
            @Schema(description = "设备上报时间（yyyy-MM-dd HH:mm:ss）")
            @JsonSerialize(using = TimestampLongSerializer.class)
            private Long occurred;
            @Schema(description = "告警阈值（来自所属塘口配置，如 {\"min\":0,\"max\":0.5,\"level\":\"紧急\"}）")
            private Object threshold;
            @Schema(description = "状态：normal 正常 / danger 越界告警（后端已按阈值判断）")
            private String status;
            @Schema(description = "阈值展示文本（后端已拼接好，前端直接展示）")
            private String thresholdText;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @Schema(description = "控制项（布尔类型的读写参数）")
        public static class ControlItem {
            @Schema(description = "标识符")
            private String identifier;
            @Schema(description = "名称")
            private String name;
            @Schema(description = "当前值")
            private Object value;
            @Schema(description = "描述")
            private String description;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "告警记录")
    public static class YUnitAlert {
        @Schema(description = "告警ID")
        private Long id;
        @Schema(description = "告警名称")
        private String name;
        @Schema(description = "告警等级")
        private String level;
        @Schema(description = "告警详情")
        private String details;
        @Schema(description = "触发设备名称")
        private String deviceName;
        @Schema(description = "所属养殖场名称")
        private String farmName;
        @Schema(description = "所属塘口名称")
        private String pondName;
        @Schema(description = "所属模块名称")
        private String moduleName;
        @Schema(description = "告警时间（yyyy-MM-dd HH:mm:ss）")
        @JsonSerialize(using = TimestampLongSerializer.class)
        private Long alertTime;
        @Schema(description = "是否已读")
        private Boolean readFlg;
        @Schema(description = "当前上报值")
        private Double value;
        @Schema(description = "预警阈值")
        private Double threshold;
        @Schema(description = "比较符（eq/neq/gt/gte/lt/lte）")
        private String cond;
        @Schema(description = "单位（如 mg/L）")
        private String unit;
        @Schema(description = "属性标识符（如 DO）")
        private String propKey;
        @Schema(description = "属性名称（如 DO（溶氧））")
        private String propName;
        @Schema(description = "告警类型（低于阈值/高于阈值）")
        private String alertType;
    }
}
