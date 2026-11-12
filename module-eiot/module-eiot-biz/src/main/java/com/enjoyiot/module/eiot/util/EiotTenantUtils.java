package com.enjoyiot.module.eiot.util;

import com.enjoyiot.framework.common.exception.ErrorCode;
import com.enjoyiot.framework.security.core.LoginUser;
import com.enjoyiot.framework.security.core.util.SecurityFrameworkUtils;
import com.enjoyiot.module.system.api.tenant.TenantApi;
import com.enjoyiot.module.system.api.tenant.dto.TenantSimpleRespDTO;

import java.util.List;
import java.util.Objects;

import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 租户工具类
 *
 * @author EnjoyIot
 */
public class EiotTenantUtils {

    /**
     * 平台租户ID
     */
    public static final Long PLATFORM_TENANT_ID = 1L;

    /**
     * 获取当前登录用户的租户ID。
     * 未登录或租户ID为空时，视为平台管理员（tenantId=1）。
     */
    public static Long getCurrentTenantId() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null) {
            return PLATFORM_TENANT_ID;
        }
        return loginUser.getTenantId() != null ? loginUser.getTenantId() : PLATFORM_TENANT_ID;
    }

    /**
     * 判断当前用户是否为平台管理员（tenantId == 1）
     */
    public static boolean isPlatformAdmin() {
        return PLATFORM_TENANT_ID.equals(getCurrentTenantId());
    }

    /**
     * 判断当前用户是否为平台管理员
     */
    public static boolean isPlatformAdmin(Long tenantId) {
        return PLATFORM_TENANT_ID.equals(tenantId);
    }

    /**
     * 校验当前登录用户对「目标租户」是否有操作权限（养殖场 / 塘口 / 模块新增与修改的通用校验）
     *
     * <p>规则：</p>
     * <ul>
     *   <li>平台管理员（tenantId = 1）：不受限制，可操作任意租户</li>
     *   <li>普通租户管理员：<b>只能操作自己的租户 + 自己的一级子租户</b></li>
     * </ul>
     *
     * <p>可见租户范围复用 {@link TenantApi#getTenantList()}（其内部已按权限过滤：
     * 平台管理员返回全部启用租户；普通租户返回自己 + 一级子租户），
     * 保证与前端「场地管理 → 租户下拉」看到的范围完全一致，避免下拉看得见、保存却报越权。</p>
     *
     * @param targetTenantId 目标租户ID（要操作的养殖场/塘口/模块所属租户）
     * @param tenantApi      租户 API，用于取当前用户可见租户列表；为 null 时退化为「只能操作自己」
     * @param errorCode      校验失败时抛出的错误码（各模块自定义文案）
     */
    public static void validateTenantPermission(Long targetTenantId, TenantApi tenantApi, ErrorCode errorCode) {
        Long currentTenantId = getCurrentTenantId();
        // 平台管理员不受限
        if (isPlatformAdmin(currentTenantId)) {
            return;
        }
        if (targetTenantId == null) {
            throw exception(errorCode);
        }
        // 自己的租户
        if (Objects.equals(targetTenantId, currentTenantId)) {
            return;
        }
        // 自己的一级子租户：TenantApi.getTenantList() 对普通租户已含子租户
        List<TenantSimpleRespDTO> visibleTenants = tenantApi == null ? null : tenantApi.getTenantList();
        if (visibleTenants != null
                && visibleTenants.stream().anyMatch(t -> Objects.equals(t.getId(), targetTenantId))) {
            return;
        }
        throw exception(errorCode);
    }
}
