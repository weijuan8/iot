
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
package com.enjoyiot.module.eiot.dal.dataobject;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.framework.tenant.core.db.TenantBaseDO;
import lombok.*;

/**
 * 告警记录 DO
 *
 * @author EnjoyIot
 */
@TableName("eiot_alert_record")
@KeySequence("eiot_alert_record_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRecordDO extends TenantBaseDO {

    /**
     * id
     */
    @TableId
    private Long id;
    /**
     * 告警时间
     */
    private Long alertTime;
    /**
     * 告警详情
     */
    private String details;
    /**
     * 告警等级
     */
    private String level;
    /**
     * 告警名称
     */
    private String name;
    /**
     * 是否已读
     */
    private Boolean readFlg;
    /**
     * 机构id
     */
    private Long deptId;
    /**
     * 触发设备ID
     */
    private Long deviceId;
    /**
     * 养殖场ID
     */
    private Long farmId;
    /**
     * 塘口ID
     */
    private Long pondId;
    /**
     * 模块ID
     */
    private Long moduleId;
    /**
     * 当前上报值
     */
    private Double value;
    /**
     * 预警阈值
     */
    private Double threshold;
    /**
     * 比较符（eq/neq/gt/gte/lt/lte）
     * 列名用 cond：condition 是 MySQL 保留字，直接用会导致 INSERT 语法错误
     */
    private String cond;
    /**
     * 单位（如 mg/L）
     */
    private String unit;
    /**
     * 属性标识符（如 DO）
     */
    private String propKey;
    /**
     * 属性名称（标识符（中文名），如 DO（溶氧））
     */
    private String propName;
    /**
     * 告警类型（低于阈值/高于阈值）
     */
    private String alertType;

    // ========== JOIN 字段（非数据库列，仅查询时填充） ==========

    @TableField(exist = false)
    private String farmName;

    @TableField(exist = false)
    private String pondName;

    @TableField(exist = false)
    private String deviceName;

    @TableField(exist = false)
    private String moduleName;

}
