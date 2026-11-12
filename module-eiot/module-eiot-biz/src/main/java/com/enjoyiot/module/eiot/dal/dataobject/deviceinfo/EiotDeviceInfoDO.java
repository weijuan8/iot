
/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com | Tel: 19918996474
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot] | Tel: 19918996474
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
package com.enjoyiot.module.eiot.dal.dataobject.deviceinfo;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.framework.mybatis.core.dataobject.BaseDO;
import lombok.*;

/**
 * 设备信息 DO
 *
 * @author EnjoyIot
 */
@TableName("eiot_device_info")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EiotDeviceInfoDO extends BaseDO {

    /**
     * 设备id
     */
    @TableId
    private Long id;
    /**
     * 设备唯一标识
     */
    private String dn;
    /**
     * 产品key
     */
    private String productKey;
    /**
     * 机构id
     */
    private Long deptId;
    /**
     * 设备属性
     */
    private String properties;
    /**
     * 别名
     */
    private String name;
    /**
     *(0:否, 1:在线, 3-未激活，4-禁用)设备状态
     */
    private Integer state;
    /**
     * 离线时间
     */
    private Long offlineTime;
    /**
     * 在线时间
     */
    private Long onlineTime;
    /**
     * 设备序列号
     */
    private String serialNo;
    /**
     * 备注
     */
    private String remark;
    /**
     * 经纬度
     */
    private Double lat;
    /**
     * 经纬度
     */
    private Double lon;
    
    private String model;

    private Long parentId;

    private String secret;

    private String addr;

    private String firmVersion;

    private Integer nodeType;

    private Boolean transparent;

    /**
     * Modbus从机地址(1-247)
     */
    private Integer slaveAddr;

    // ========== JOIN 字段（非数据库列，连表查询时填充） ==========

    @TableField(exist = false)
    private Long farmId;

    @TableField(exist = false)
    private String farmName;

    @TableField(exist = false)
    private Long pondId;

    @TableField(exist = false)
    private String pondName;

    @TableField(exist = false)
    private Long moduleId;

    /** 设备所属租户ID（来自 y_module_device.owner_tenant_id，JOIN 查询时填充，区别于表自身的 tenant_id） */
    @TableField(exist = false)
    private Long ownerTenantId;

    @TableField(exist = false)
    private String alertThresholdsJson;

    @TableField(exist = false)
    private String alertTemplateIdsJson;

    @TableField(exist = false)
    private Boolean pondAlertEnabled;

}
