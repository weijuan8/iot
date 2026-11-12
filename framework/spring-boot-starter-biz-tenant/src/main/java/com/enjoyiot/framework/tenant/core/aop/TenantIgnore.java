package com.enjoyiot.framework.tenant.core.aop;

import java.lang.annotation.*;

/**
 * 忽略租户过滤 — 标记指定方法不进行租户的自动过滤
 *
 * <p>标注此注解的方法执行期间，MyBatis Plus 的 TenantLineInnerInterceptor
 * 不会在 SQL 中自动注入 WHERE tenant_id = ? 条件，从而实现跨租户数据访问。</p>
 *
 * <h3>工作原理</h3>
 * <pre>
 * @TenantIgnore
 *   → TenantIgnoreAspect 拦截
 *     → 方法执行前：TenantContextHolder.setIgnore(true)
 *     → 方法体中：MyBatis 查询 → TenantDatabaseInterceptor.ignoreTable() 返回 true → 不注入 tenant_id
 *     → 方法执行后：恢复原来的 ignore 状态
 * </pre>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li>平台管理员查看所有租户的数据（如运维端塘口管理）</li>
 *   <li>定时任务需要处理全量数据</li>
 *   <li>系统初始化、数据迁移等全局操作</li>
 * </ul>
 *
 * <h3>重要：INSERT 时需手动设置 tenant_id</h3>
 * <p>由于注解跳过了 MyBatis 的自动 tenant_id 注入，INSERT 操作需要<b>手动设置</b>
 * DO 对象的 tenantId 字段，否则数据库会写入默认值 0。</p>
 *
 * <h3>局限</h3>
 * <ul>
 *   <li>只影响 DB 层（MyBatis 拦截器），不影响 Redis（Redis 按 Key 隔离）和 MQ</li>
 *   <li>MQ 消费端如需忽略租户，需在 Consumer 方法上单独标注</li>
 * </ul>
 *
 * @author EnjoyIot
 * @see TenantIgnoreAspect
 * @see com.enjoyiot.framework.tenant.core.db.TenantDatabaseInterceptor
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface TenantIgnore {
}
