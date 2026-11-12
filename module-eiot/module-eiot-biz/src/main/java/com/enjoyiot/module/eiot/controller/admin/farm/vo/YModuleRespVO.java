package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模块 响应 VO（详情/列表）
 * 字段名匹配前端 ModuleVO 接口
 *
 * @author EnjoyIot
 */
@Data
public class YModuleRespVO {

    private Long id;

    private Long tenantId;

    /** 租户名称（冗余，前端展示用） */
    private String tenantName;

    private Long farmId;

    /** 养殖场名称（冗余，前端展示用） */
    private String farmName;

    private Long pondId;

    /** 塘口名称（冗余，前端展示用） */
    private String pondName;

    private String moduleCode;

    private String moduleName;

    private String installPosition;

    private String installAt;

    private String installBy;

    private String powerSupplyType;

    /** 绑定的设备列表 */
    private List<YModuleDeviceRespVO> devices;

    /** 绑定的设备数量（列表查询时通过连表计算，详情接口另行查询） */
    private Integer deviceCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String updater;
}
