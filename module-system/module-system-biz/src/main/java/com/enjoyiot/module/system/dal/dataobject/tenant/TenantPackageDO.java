package com.enjoyiot.module.system.dal.dataobject.tenant;

import com.enjoyiot.framework.common.enums.CommonStatusEnum;
import com.enjoyiot.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.*;

import java.util.Set;

/**
 * 租户套餐 DO
 *
 * <h3>作用</h3>
 * <p>定义每个套餐包含哪些菜单权限。平台创建租户时选择套餐，
 * 系统自动将套餐的 menuIds 分配给租户的 tenant_admin 角色。</p>
 *
 * <h3>菜单隔离流程</h3>
 * <pre>
 * TenantPackageDO.menuIds = [100, 101, 3200, ...]
 *   → 创建租户时: assignRoleMenu(tenant_admin_role_id, package.menuIds)
 *   → 租户管理员登录后只能看到这些菜单
 * </pre>
 *
 * <h3>重要</h3>
 * <p>继承 {@link BaseDO} 而非 TenantBaseDO，因为套餐是全局的，不属于某个租户。
 * menuIds 用 JacksonTypeHandler 存为 JSON 数组在数据库中。</p>
 *
 * @author EnjoyIot
 */
@TableName(value = "system_tenant_package", autoResultMap = true)
@KeySequence("system_tenant_package_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TenantPackageDO extends BaseDO {

    /**
     * 套餐编号，自增
     */
    private Long id;
    /**
     * 套餐名，唯一
     */
    private String name;
    /**
     * 租户套餐状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;
    /**
     * 备注
     */
    private String remark;
    /**
     * 关联的菜单编号
     *
     * <p>格式: JSON 数组，如 [100, 101, 102, 3200, 3220]
     *
     * <p>这个字段是整个菜单隔离的核心：
     * <ol>
     *   <li>平台创建租户套餐时勾选菜单 → 写入此字段</li>
     *   <li>创建租户时 → 自动把 menuIds 赋给 tenant_admin 角色</li>
     *   <li>套餐变更时 → 重新计算租户下所有角色的菜单权限</li>
     * </ol>
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<Long> menuIds;

}
