package com.enjoyiot.module.eiot.dal.dataobject.farm;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.framework.tenant.core.db.TenantBaseDO;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDate;

/**
 * 模块（设备区域）DO
 * 一套设备 = 一个模块/区域
 *
 * @author EnjoyIot
 */
@TableName("y_module")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YModuleDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 关联养殖场ID */
    private Long farmId;

    /** 关联塘口ID */
    private Long pondId;

    /** 模块编码/SN（自动生成唯一） */
    private String moduleCode;

    /** 模块名称 */
    private String moduleName;

    /** 塘内安装位置 */
    private String installPosition;

    /** 安装日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate installAt;

    /** 安装人 */
    private String installBy;

    /** 网络类型 */
    private String networkType;

    /** 供电方式 */
    private String powerSupplyType;
}
