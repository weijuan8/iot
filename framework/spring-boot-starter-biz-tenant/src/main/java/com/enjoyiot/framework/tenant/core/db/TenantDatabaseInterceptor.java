package com.enjoyiot.framework.tenant.core.db;

import cn.hutool.core.collection.CollUtil;
import com.enjoyiot.framework.tenant.config.TenantProperties;
import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.toolkit.SqlParserUtils;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.HashSet;
import java.util.Set;

/**
 * MyBatis Plus 多租户拦截器 — 自动在 SQL 中注入 tenant_id 条件
 *
 * <h3>工作原理</h3>
 * <p>实现 MyBatis Plus 的 {@link TenantLineHandler} 接口，
 * 在每条 SQL 执行前自动添加租户过滤：</p>
 *
 * <b>SELECT/UPDATE/DELETE</b> — 自动追加 WHERE tenant_id = ?：
 * <pre>
 *   SELECT * FROM device WHERE state = 1
 *   → SELECT * FROM device WHERE state = 1 AND tenant_id = 1
 * </pre>
 *
 * <b>INSERT</b> — 自动追加 tenant_id 列和值：
 * <pre>
 *   INSERT INTO device (name, state) VALUES (?, ?)
 *   → INSERT INTO device (name, state, tenant_id) VALUES (?, ?, 1)
 * </pre>
 *
 * <h3>何时跳过（ignoreTable 返回 true）</h3>
 * <ol>
 *   <li>TenantContextHolder.isIgnore() = true — 通过 @TenantIgnore 注解或 TenantUtils.executeIgnore() 设置</li>
 *   <li>表名在 ignore-tables 配置中 — 如 system_tenant、system_menu 等全局表</li>
 * </ol>
 *
 * <h3>与 @TenantIgnore 的配合</h3>
 * <p>@TenantIgnore → TenantIgnoreAspect → setIgnore(true) → 本拦截器 ignoreTable() 返回 true → 跳过租户处理</p>
 *
 * @author EnjoyIot
 * @see TenantContextHolder
 * @see com.enjoyiot.framework.tenant.core.aop.TenantIgnore
 */
public class TenantDatabaseInterceptor implements TenantLineHandler {

    /**
     * 需要忽略租户过滤的表名集合
     *
     * <p>包含：
     * <ul>
     *   <li>配置文件中 ignore-tables 指定的表（如 system_tenant、system_menu）</li>
     *   <li>DUAL 表（Oracle 生成主键时会查询此表）</li>
     * </ul>
     * 同时存小写和大写，兼容不同数据库的大小写习惯。
     */
    private final Set<String> ignoreTables = new HashSet<>();

    public TenantDatabaseInterceptor(TenantProperties properties) {
        // 不同 DB 下，大小写的习惯不同，所以同时存小写和大写
        properties.getIgnoreTables().forEach(table -> {
            ignoreTables.add(table.toLowerCase());
            ignoreTables.add(table.toUpperCase());
        });
        // Oracle 生成主键时查询 DUAL 表，自动拼接 tenant_id 会导致报错
        ignoreTables.add("DUAL");
    }

    /**
     * 获取当前租户 ID
     *
     * <p>MyBatis Plus 在生成 SQL 时调用：
     * <ul>
     *   <li>SELECT/UPDATE/DELETE：作为 WHERE tenant_id = ? 的参数值</li>
     *   <li>INSERT：作为 tenant_id 列的值</li>
     * </ul>
     *
     * @return 当前租户 ID
     */
    @Override
    public Expression getTenantId() {
        return new LongValue(TenantContextHolder.getRequiredTenantId());
    }

    /**
     * 获取租户字段名
     *
     * <p>MyBatis Plus 会识别表中是否有此字段，有则自动注入，无则跳过。</p>
     */
    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    /**
     * 判断是否忽略当前表的租户处理
     *
     * <p>两种情况下跳过：
     * <ol>
     *   <li>全局忽略：TenantContextHolder.isIgnore() = true
     *       → 由 @TenantIgnore 注解或 TenantUtils.executeIgnore() 触发</li>
     *   <li>表级忽略：表名在 ignore-tables 配置中
     *       → system_tenant、system_menu 等全局表不需要租户隔离</li>
     * </ol>
     *
     * @param tableName 当前 SQL 涉及的表名
     * @return true 表示不添加租户过滤
     */
    @Override
    public boolean ignoreTable(String tableName) {
        return TenantContextHolder.isIgnore() // 情况一：全局忽略多租户（@TenantIgnore 触发）
                || CollUtil.contains(ignoreTables, SqlParserUtils.removeWrapperSymbol(tableName)); // 情况二：表在忽略列表中
    }

}
