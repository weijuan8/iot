package com.enjoyiot.framework.security.core.service;

import cn.hutool.core.collection.CollUtil;
import com.enjoyiot.framework.security.core.LoginUser;
import com.enjoyiot.framework.security.core.util.SecurityFrameworkUtils;
import com.enjoyiot.module.system.api.permission.PermissionApi;
import lombok.AllArgsConstructor;

import java.util.Arrays;

import static com.enjoyiot.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

/**
 * SecurityFrameworkService 实现 — 权限和角色校验的入口
 *
 * <h3>在 SpEL 中使用</h3>
 * <pre>
 * @PreAuthorize("@ss.hasPermission('operations:pond:query')")  → 校验菜单权限
 * @PreAuthorize("@ss.hasRole('super_admin')")                   → 校验角色
 * </pre>
 *
 * <h3>校验链路</h3>
 * <pre>
 * hasRole("super_admin")
 *   → PermissionApi.hasAnyRoles(userId, "super_admin")
 *     → 查 system_user_role 拿到用户的 roleIds
 *     → 查 system_role 匹配 role.code = "super_admin"
 * </pre>
 *
 * <h3>hasRole vs hasPermission</h3>
 * <ul>
 *   <li><b>hasRole</b>: 判断用户是否拥有指定角色（按 role.code 匹配）</li>
 *   <li><b>hasPermission</b>: 判断用户是否拥有指定菜单权限（按 menu.permission 匹配）</li>
 * </ul>
 * <p>通常菜单可见性用 hasRole（超级管理员 vs 租户管理员），
 * 按钮级别限制用 hasPermission（system:user:create 等）。</p>
 *
 * @author EnjoyIot
 */
@AllArgsConstructor
public class SecurityFrameworkServiceImpl implements SecurityFrameworkService {

    private final PermissionApi permissionApi;

    /**
     * 判断当前登录用户是否拥有指定菜单权限
     *
     * <p>实现：通过 PermissionApi 查询用户角色 → 角色菜单 → 匹配 permission 字段</p>
     *
     * @param permission 权限标识，如 "operations:pond:query"
     */
    @Override
    public boolean hasPermission(String permission) {
        return hasAnyPermissions(permission);
    }

    @Override
    public boolean hasAnyPermissions(String... permissions) {
        Long userId = getLoginUserId();
        if (userId == null) {
            return false;
        }
        return permissionApi.hasAnyPermissions(userId, permissions);
    }

    /**
     * 判断当前登录用户是否拥有指定角色
     *
     * <p>这是我们在塘口管理中用到的关键方法：
     * 通过 hasRole("super_admin") 判断是否忽略租户过滤。</p>
     *
     * @param role 角色编码，如 "super_admin"、"tenant_admin"
     */
    @Override
    public boolean hasRole(String role) {
        return hasAnyRoles(role);
    }

    @Override
    public boolean hasAnyRoles(String... roles) {
        Long userId = getLoginUserId();
        if (userId == null) {
            return false;
        }
        // 调 module-system 的 PermissionApi，查询用户-角色关联
        return permissionApi.hasAnyRoles(userId, roles);
    }

    @Override
    public boolean hasScope(String scope) {
        return hasAnyScopes(scope);
    }

    @Override
    public boolean hasAnyScopes(String... scope) {
        LoginUser user = SecurityFrameworkUtils.getLoginUser();
        if (user == null) {
            return false;
        }
        return CollUtil.containsAny(user.getScopes(), Arrays.asList(scope));
    }

}
