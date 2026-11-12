package com.enjoyiot.module.eiot.controller.admin.farm.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 模块-设备关联 响应 VO
 * 字段名匹配前端 DeviceBindingVO 接口
 *
 * @author EnjoyIot
 */
@Data
public class YModuleDeviceRespVO {

    /** 绑定记录 ID */
    private Long id;

    /** 设备 ID */
    private Long deviceId;

    /** 设备编码（前端 deviceCode） */
    private String deviceCode;

    /** 设备名称（前端 deviceName） */
    private String deviceName;

    /** 设备类型（前端 deviceType） */
    private String deviceType;

    /** 绑定时间（前端 bindAt） */
    private String bindAt;
}
