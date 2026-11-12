package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 养殖场 响应 VO（详情/列表）
 * 字段名匹配前端 FarmVO 接口
 *
 * @author EnjoyIot
 */
@Data
public class YFarmRespVO {

    private Long id;

    private Long tenantId;

    /** 租户名称（冗余，前端展示用） */
    private String tenantName;

    private String farmCode;

    private String farmName;

    private String farmType;

    private java.math.BigDecimal totalAreaMu;

    private Integer pondCount;

    private java.math.BigDecimal buildingAreaMu;

    private Double longitude;

    private Double latitude;

    private java.math.BigDecimal altitudeM;

    private String province;

    private String city;

    private String district;

    private String address;

    // 基础设施
    private String powerSupplyType;

    private java.math.BigDecimal powerCapacityKw;

    private String hasBackupPower;

    private String waterSource;

    private String hasWaterTower;

    private java.math.BigDecimal waterTowerVolumeM3;

    private String drainageType;

    private String networkType;

    /** 创建时间（前端字段名 createdAt） */
    private LocalDateTime createdAt;

    /** 更新时间（前端字段名 updatedAt） */
    private LocalDateTime updatedAt;

    /** 创建人（前端字段名 createdBy） */
    private String createdBy;

    /** 更新人（前端字段名 updatedBy） */
    private String updatedBy;
}
