package com.enjoyiot.framework.tenant.core.security;

import cn.hutool.core.collection.CollUtil;
import com.enjoyiot.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.framework.common.util.servlet.ServletUtils;
import com.enjoyiot.framework.security.core.LoginUser;
import com.enjoyiot.framework.security.core.util.SecurityFrameworkUtils;
import com.enjoyiot.framework.tenant.config.TenantProperties;
import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.framework.tenant.core.service.TenantFrameworkService;
import com.enjoyiot.framework.web.config.WebProperties;
import com.enjoyiot.framework.web.core.filter.ApiRequestFilter;
import com.enjoyiot.framework.web.core.handler.GlobalExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * 多租户 Security Web 过滤器 — 租户等级的安全校验
 *
 * <h3>执行顺序</h3>
 * <p>在 {@link com.enjoyiot.framework.tenant.core.web.TenantContextWebFilter} 之后执行，
 * 此时 TenantContextHolder 中可能已有 tenant-id（从 Header 解析而来）。</p>
 *
 * <h3>三步校验</h3>
 * <ol>
 *   <li><b>用户权限校验</b>：登录用户是否有权访问目标租户？
 *       <ul>
 *         <li>请求未带 tenant-id → 使用用户自己的 tenant_id</li>
 *         <li>请求带的 tenant-id 与用户 tenant_id 不一致 → 403 越权拒绝</li>
 *       </ul>
 *   </li>
 *   <li><b>租户合法性校验</b>：
 *       <ul>
 *         <li>URL 不在 ignore-urls 中且未传 tenant-id → 400 拒绝</li>
 *         <li>URL 不在 ignore-urls 中 → 校验租户是否被禁用/已过期</li>
 *       </ul>
 *   </li>
 *   <li><b>忽略租户 URL 处理</b>：
 *       <ul>
 *         <li>URL 在 ignore-urls 中且 tenant-id 为空 → 设置 IGNORE=true</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>重要：超级管理员的处理</h3>
 * <p>超级管理员登录后 user.getTenantId() = 1（EnjoyIot 默认租户）。
 * 即使 URL 在 ignore-urls 中，只要用户已登录且 tenant-id 不为空，
 * 本 Filter 不会自动设置 IGNORE=true。
 * 此时需要配合 Controller 方法上的 @TenantIgnore 注解来实现跨租户查询。</p>
 *
 * @author EnjoyIot
 * @see com.enjoyiot.framework.tenant.core.web.TenantContextWebFilter
 * @see com.enjoyiot.framework.tenant.core.aop.TenantIgnore
 */
@Slf4j
public class TenantSecurityWebFilter extends ApiRequestFilter {

    private final TenantProperties tenantProperties;
    private final AntPathMatcher pathMatcher;
    private final GlobalExceptionHandler globalExceptionHandler;
    private final TenantFrameworkService tenantFrameworkService;

    public TenantSecurityWebFilter(TenantProperties tenantProperties,
                                   WebProperties webProperties,
                                   GlobalExceptionHandler globalExceptionHandler,
                                   TenantFrameworkService tenantFrameworkService) {
        super(webProperties);
        this.tenantProperties = tenantProperties;
        this.pathMatcher = new AntPathMatcher();
        this.globalExceptionHandler = globalExceptionHandler;
        this.tenantFrameworkService = tenantFrameworkService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long tenantId = TenantContextHolder.getTenantId();

        // ========== 步骤 1：登录用户越权校验 ==========
        // 如果用户已登录，确保其只能访问自己所属租户的数据
        LoginUser user = SecurityFrameworkUtils.getLoginUser();
        if (user != null) {
            // 请求未带 tenant-id → 自动使用用户自己的租户 ID
            if (tenantId == null) {
                tenantId = user.getTenantId();
                TenantContextHolder.setTenantId(tenantId);
            // 请求带的 tenant-id 与用户的不一致 → 阻止越权访问
            } else if (!Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId())) {
                log.error("[doFilterInternal][租户({}) User({}/{}) 越权访问租户({}) URL({}/{})]",
                        user.getTenantId(), user.getId(), user.getUserType(),
                        TenantContextHolder.getTenantId(), request.getRequestURI(), request.getMethod());
                ServletUtils.writeJSON(response, CommonResult.error(GlobalErrorCodeConstants.FORBIDDEN.getCode(),
                        "您无权访问该租户的数据"));
                return;
            }
        }

        // ========== 步骤 2 & 3：URL 级别校验 ==========
        if (!isIgnoreUrl(request)) {
            // 非忽略 URL：必须传 tenant-id，且租户状态合法（未被禁用/未过期）
            if (tenantId == null) {
                log.error("[doFilterInternal][URL({}/{}) 未传递租户编号]", request.getRequestURI(), request.getMethod());
                ServletUtils.writeJSON(response, CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                        "请求的租户标识未传递，请进行排查"));
                return;
            }
            // 校验租户是否被禁用或已过期
            try {
                tenantFrameworkService.validTenant(tenantId);
            } catch (Throwable ex) {
                CommonResult<?> result = globalExceptionHandler.allExceptionHandler(request, ex);
                ServletUtils.writeJSON(response, result);
                return;
            }
        } else {
            // 忽略 URL（如 /operations/pond/**、回调接口等）：
            // 如果 tenant-id 为空，设置全局忽略标志，让 MyBatis 拦截器跳过租户过滤
            if (tenantId == null) {
                TenantContextHolder.setIgnore(true);
            }
        }

        // 继续过滤链
        chain.doFilter(request, response);
    }

    /**
     * 判断当前 URL 是否需要忽略租户校验
     *
     * <p>先做精确匹配（O(1)），再做 Ant 路径匹配（O(n) 遍历），保证性能。</p>
     *
     * @param request HTTP 请求
     * @return true 表示跳过租户校验
     */
    private boolean isIgnoreUrl(HttpServletRequest request) {
        // 快速精确匹配
        if (CollUtil.contains(tenantProperties.getIgnoreUrls(), request.getRequestURI())) {
            return true;
        }
        // Ant 路径匹配（如 /admin-api/operations/pond/**）
        for (String url : tenantProperties.getIgnoreUrls()) {
            if (pathMatcher.match(url, request.getRequestURI())) {
                return true;
            }
        }
        return false;
    }

}
