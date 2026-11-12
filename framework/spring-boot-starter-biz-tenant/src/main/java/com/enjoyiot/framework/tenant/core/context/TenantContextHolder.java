package com.enjoyiot.framework.tenant.core.context;

import com.enjoyiot.framework.common.enums.DocumentEnum;
import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 多租户上下文 Holder
 *
 * <p>使用 TransmittableThreadLocal 存储当前请求的租户信息，
 * 保证在线程池、异步调用等场景下也能正确传递租户上下文。</p>
 *
 * <h3>两个关键状态</h3>
 * <ul>
 *   <li><b>TENANT_ID</b>：当前租户编号。为 null 时表示未设置租户</li>
 *   <li><b>IGNORE</b>：是否忽略租户过滤。true 时 MyBatis 拦截器跳过自动注入 WHERE tenant_id = ?</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>请求进入 → TenantContextWebFilter 从 HTTP Header 读取 tenant-id 写入 TENANT_ID</li>
 *   <li>请求处理中 → MyBatis 拦截器读取 TENANT_ID 自动注入 SQL 过滤条件</li>
 *   <li>请求结束 → TenantContextWebFilter 调用 clear() 清理，防止内存泄漏</li>
 * </ol>
 *
 * <h3>忽略租户的场景</h3>
 * <ul>
 *   <li>平台管理员查看所有租户数据时，通过 @TenantIgnore 注解设置 IGNORE=true</li>
 *   <li>全局表（system_tenant、system_menu 等）通过 ignore-tables 配置跳过</li>
 * </ul>
 *
 * @author EnjoyIot
 * @see com.enjoyiot.framework.tenant.core.web.TenantContextWebFilter
 * @see com.enjoyiot.framework.tenant.core.db.TenantDatabaseInterceptor
 */
public class TenantContextHolder {

    /**
     * 当前租户编号
     *
     * <p>使用 TransmittableThreadLocal 而非普通 ThreadLocal：
     * <ul>
     *   <li>普通 ThreadLocal：线程池复用线程时，上次请求的租户 ID 可能泄漏到下次请求</li>
     *   <li>TransmittableThreadLocal：配合 TtlRunnable/TtlCallable，线程池场景下自动传递和清理</li>
     * </ul>
     */
    private static final ThreadLocal<Long> TENANT_ID = new TransmittableThreadLocal<>();

    /**
     * 是否忽略租户
     *
     * <p>为 true 时，MyBatis Plus 的 TenantLineInnerInterceptor 会跳过当前 SQL 的租户过滤。
     * 由 @TenantIgnore 注解或 TenantUtils.executeIgnore() 设置。</p>
     */
    private static final ThreadLocal<Boolean> IGNORE = new TransmittableThreadLocal<>();

    /**
     * 获得租户编号，可能为 null（未设置租户上下文的场景，如忽略 URL）
     *
     * @return 租户编号，可能为 null
     */
    public static Long getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * 获得租户编号，如果不存在则抛出 NullPointerException
     *
     * <p>MyBatis 拦截器在构建 SQL 时调用此方法，获取要注入的 tenant_id 值。
     * 正常业务请求必须携带 tenant-id，否则此处抛异常。</p>
     *
     * @return 租户编号（保证非 null）
     * @throws NullPointerException 如果 TENANT_ID 未设置
     */
    public static Long getRequiredTenantId() {
        Long tenantId = getTenantId();
        if (tenantId == null) {
            throw new NullPointerException("TenantContextHolder 不存在租户编号！可参考文档："
                + DocumentEnum.TENANT.getUrl());
        }
        return tenantId;
    }

    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static void setIgnore(Boolean ignore) {
        IGNORE.set(ignore);
    }

    /**
     * 当前是否忽略租户
     *
     * <p>由 TenantDatabaseInterceptor.ignoreTable() 调用，
     * 决定是否对当前 SQL 添加租户过滤条件。</p>
     *
     * @return true 表示跳过租户过滤
     */
    public static boolean isIgnore() {
        return Boolean.TRUE.equals(IGNORE.get());
    }

    /**
     * 清理 ThreadLocal，防止内存泄漏和跨请求污染
     *
     * <p>在 TenantContextWebFilter 的 finally 块中调用。</p>
     */
    public static void clear() {
        TENANT_ID.remove();
        IGNORE.remove();
    }

}
