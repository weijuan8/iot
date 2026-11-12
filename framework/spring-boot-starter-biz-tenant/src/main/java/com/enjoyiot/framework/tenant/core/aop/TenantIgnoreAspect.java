package com.enjoyiot.framework.tenant.core.aop;

import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * @TenantIgnore 注解的 AOP 实现 — 在方法执行期间临时跳过租户过滤
 *
 * <h3>执行流程</h3>
 * <pre>
 * 1. 方法执行前：TenantContextHolder.setIgnore(true) — 通知 MyBatis 拦截器跳过租户处理
 * 2. 方法体中：所有 DB 操作不受 tenant_id 限制
 * 3. 方法执行后（finally）：恢复原来的 ignore 状态
 * </pre>
 *
 * <h3>与 TenantUtils.executeIgnore 的关系</h3>
 * <p>两者逻辑一致，只是一个作用在方法级别（AOP），一个作用在代码块级别（手动调用）。
 * 都是通过设置 IGNORE 标志来控制 MyBatis 拦截器的行为。</p>
 *
 * <h3>典型用法</h3>
 * <pre>
 * // 运维端查询所有租户的塘口
 * @PostMapping("/page")
 * @TenantIgnore
 * public CommonResult page(@RequestBody PondPageReqVO reqVO) {
 *     // 此方法中所有 DB 操作不受租户限制
 *     return success(groupMapper.selectPage(reqVO, query));
 * }
 * </pre>
 *
 * @author EnjoyIot
 * @see TenantIgnore
 * @see TenantContextHolder
 * @see TenantUtils#executeIgnore(Runnable)
 */
@Aspect
@Slf4j
public class TenantIgnoreAspect {

    /**
     * 拦截所有标注了 @TenantIgnore 的方法
     *
     * <p>在方法执行前设置 IGNORE=true，执行后恢复原状态。
     * 使用 try-finally 确保即使方法抛出异常也能正确恢复。</p>
     *
     * @param joinPoint    切点
     * @param tenantIgnore 注解实例
     * @return 方法原始返回值
     */
    @Around("@annotation(tenantIgnore)")
    public Object around(ProceedingJoinPoint joinPoint, TenantIgnore tenantIgnore) throws Throwable {
        // 保存当前 ignore 状态，方法执行后恢复
        Boolean oldIgnore = TenantContextHolder.isIgnore();
        try {
            // 设置忽略租户标志
            // → TenantDatabaseInterceptor.ignoreTable() 返回 true → SQL 不加 WHERE tenant_id = ?
            TenantContextHolder.setIgnore(true);
            return joinPoint.proceed();
        } finally {
            // 恢复原来的 ignore 状态，避免影响后续方法
            TenantContextHolder.setIgnore(oldIgnore);
        }
    }

}
