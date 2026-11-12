package com.enjoyiot.framework.tenant.core.web;

import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.framework.web.core.util.WebFrameworkUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 多租户上下文 Web 过滤器 — 请求链路中最先执行的租户过滤器
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>从 HTTP Header <b>tenant-id</b> 中解析租户编号</li>
 *   <li>写入 {@link TenantContextHolder}，供后续 Filter、Controller、MyBatis 拦截器使用</li>
 *   <li>请求结束时清理 ThreadLocal，防止内存泄漏</li>
 * </ol>
 *
 * <h3>执行顺序</h3>
 * <p>在所有租户相关过滤器中<b>最先执行</b>（Order: TENANT_CONTEXT_FILTER），
 * 确保后续的 TenantSecurityWebFilter 能拿到租户上下文。</p>
 *
 * <h3>请求流程</h3>
 * <pre>
 * 前端请求带 Header tenant-id: 1
 *   → 本 Filter 读取 Header 写入 TenantContextHolder.TENANT_ID = 1
 *   → TenantSecurityWebFilter 校验用户是否有权访问租户 1
 *   → Controller 处理业务
 *   → MyBatis 拦截器读取 TENANT_ID 自动注入 SQL
 *   → 本 Filter finally 块调用 clear() 清理
 * </pre>
 *
 * @author EnjoyIot
 * @see TenantContextHolder
 * @see TenantSecurityWebFilter
 */
public class TenantContextWebFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 从 HTTP Header 解析租户 ID 并写入上下文
        Long tenantId = WebFrameworkUtils.getTenantId(request);
        if (tenantId != null) {
            TenantContextHolder.setTenantId(tenantId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 请求结束，清理 ThreadLocal
            // 必须清理！否则线程池复用线程时，上次请求的租户 ID 会污染下一次请求
            TenantContextHolder.clear();
        }
    }

}
