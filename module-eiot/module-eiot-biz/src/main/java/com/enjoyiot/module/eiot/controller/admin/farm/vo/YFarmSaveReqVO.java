package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 养殖场 创建/更新 Request VO
 *
 * @author EnjoyIot
 */
@Data
public class YFarmSaveReqVO {

    private Long id;

    /**
     * 所属租户ID（创建时必填，平台管理员选择养殖场归属哪个租户）
     */
    @NotNull(message = "所属租户不能为空")
    private Long tenantId;

    /**
     * 场编码（创建时后端自动生成，格式：FARM-yyyyMMdd-5位随机数）
     */
    private String farmCode;

    @NotBlank(message = "养殖场名称不能为空")
    private String farmName;

    @NotBlank(message = "养殖场类型不能为空")
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
}
