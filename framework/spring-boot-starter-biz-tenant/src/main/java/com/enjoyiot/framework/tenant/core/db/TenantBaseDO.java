package com.enjoyiot.framework.tenant.core.db;

import com.enjoyiot.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 多租户实体基类 — 所有需要租户隔离的表对应的 DO 应继承此类
 *
 * <h3>使用规则</h3>
 * <ul>
 *   <li><b>需要租户隔离的表</b> → DO 继承 TenantBaseDO → MyBatis 拦截器自动加 WHERE tenant_id = ?</li>
 *   <li><b>全局表</b>（如 system_tenant、system_menu）→ DO 继承 BaseDO → 不自动过滤</li>
 * </ul>
 *
 * <h3>工作原理</h3>
 * <p>MyBatis Plus 的 TenantLineInnerInterceptor 会检查表是否有 tenant_id 列，
 * 如果有且 ignoreTable() 返回 false，则自动注入租户过滤条件。
 * 继承此类只是标记"此表需要租户隔离"，真正起作用的是数据库表中的 tenant_id 列和拦截器。</p>
 *
 * <h3>示例</h3>
 * <pre>
 * // 需要租户隔离的设备表
 * public class EiotDeviceInfoDO extends TenantBaseDO {
 *     private Long id;
 *     private String dn;
 *     // tenantId 字段由父类提供
 * }
 *
 * // 全局表，不需要租户隔离
 * public class MenuDO extends BaseDO {
 *     private Long id;
 *     private String name;
 *     // 没有 tenantId 字段
 * }
 * </pre>
 *
 * <h3>数据库要求</h3>
 * <p>继承此类的 DO 对应的数据库表必须包含 tenant_id 列：</p>
 * <pre>
 * CREATE TABLE xxx (
 *     ...
 *     tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号',
 *     ...
 * );
 * </pre>
 *
 * @author EnjoyIot
 * @see BaseDO
 * @see TenantDatabaseInterceptor
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TenantBaseDO extends BaseDO {

    /**
     * 多租户编号
     *
     * <p>此字段由 MyBatis Plus 拦截器在 INSERT 时自动注入当前租户 ID，
     * SELECT/UPDATE/DELETE 时自动作为 WHERE 条件。
     * 除非方法标注了 @TenantIgnore，此时需要手动设置此字段。</p>
     */
    private Long tenantId;

}
