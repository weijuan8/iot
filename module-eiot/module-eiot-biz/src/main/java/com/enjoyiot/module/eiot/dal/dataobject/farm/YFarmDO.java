package com.enjoyiot.module.eiot.dal.dataobject.farm;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.framework.tenant.core.db.TenantBaseDO;
import lombok.*;

/**
 * 养殖场 DO
 *
 * @author EnjoyIot
 */
@TableName("y_farm")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YFarmDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 场编码（业务唯一） */
    private String farmCode;

    /** 养殖场名称 */
    private String farmName;

    /** 养殖场类型：养殖基地/育苗场/综合场 */
    private String farmType;

    /** 总占地面积（亩） */
    private java.math.BigDecimal totalAreaMu;

    /** 塘口数（统计冗余） */
    private Integer pondCount;

    /** 建筑设施面积（亩） */
    private java.math.BigDecimal buildingAreaMu;

    /** 经度 */
    private Double longitude;

    /** 纬度 */
    private Double latitude;

    /** 海拔（米） */
    private java.math.BigDecimal altitudeM;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址 */
    private String address;

    // ========== 基础设施 ==========

    /** 供电方式：市电/太阳能/风能 */
    private String powerSupplyType;

    /** 供电容量(kW) */
    private java.math.BigDecimal powerCapacityKw;

    /** 是否有备用电源：是/否 */
    private String hasBackupPower;

    /** 水源：江河/湖泊/地下井/海水/中水/自来水/雨水 */
    private String waterSource;

    /** 是否有水塔：是/否 */
    private String hasWaterTower;

    /** 水塔容量(m³) */
    private java.math.BigDecimal waterTowerVolumeM3;

    /** 排水方式：自排/抽排/循环 */
    private String drainageType;

    /** 网络类型：4G/5G/WiFi/网线/LoRa */
    private String networkType;
}
