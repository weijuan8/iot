package com.enjoyiot.module.eiot.dal.dataobject.farm;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模块-设备关联 DO
 * 一个设备只能属于一个模块
 * 注意：此关联表不需要租户隔离和软删除，使用物理删除
 * 不继承 BaseDO，避免 MyBatis-Plus 自动拼接 deleted=0 条件
 *
 * @author EnjoyIot
 */
@TableName("y_module_device")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YModuleDeviceDO implements Serializable {

    @TableId
    private Long id;

    /** 模块ID */
    private Long moduleId;

    /** 设备ID（关联 eiot_device_info.id） */
    private Long deviceId;

    /** 养殖场ID（冗余，绑定设备时从模块复制，便于查询设备所属养殖场） */
    private Long farmId;

    /** 塘口ID（冗余，绑定设备时从模块复制，便于查询设备所属塘口） */
    private Long pondId;

    /**
     * 设备所属租户ID（冗余，绑定设备时从设备表复制）
     * 注意：故意不用 tenant_id 命名，避免被 MyBatis-Plus 租户拦截器误扫描
     */
    private Long ownerTenantId;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 创建者 */
    @TableField(fill = FieldFill.INSERT)
    private String creator;

    /** 更新者 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updater;
}
