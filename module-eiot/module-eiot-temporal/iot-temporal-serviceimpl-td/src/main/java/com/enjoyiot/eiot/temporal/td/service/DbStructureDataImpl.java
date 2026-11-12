package com.enjoyiot.eiot.temporal.td.service;


import com.enjoyiot.eiot.IDbStructureData;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.eiot.temporal.td.config.Constants;
import com.enjoyiot.eiot.temporal.td.dm.*;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.enjoyiot.eiot.common.enums.ErrorCodeConstants.*;
import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 【TDengine 数据库结构管理】— 超级表创建 / 修改 / 初始化
 *
 * <h2>职责</h2>
 * 根据物模型定义自动创建和修改 TDengine 超级表（STable），
 * 是"物模型配置 → 数据库结构"的自动同步引擎。
 *
 * <h2>核心方法</h2>
 * <table>
 *   <tr><th>方法</th><th>触发时机</th><th>作用</th></tr>
 *   <tr><td>initDbStructure()</td><td>应用启动 @PostConstruct</td><td>创建全局超级表（消息日志、规则日志等）</td></tr>
 *   <tr><td>defineThingModel()</td><td>首次保存物模型</td><td>CREATE STABLE IF NOT EXISTS</td></tr>
 *   <tr><td>updateThingModel()</td><td>修改物模型</td><td>对比新旧差异 → ALTER STABLE ADD/MODIFY/DROP COLUMN</td></tr>
 * </table>
 *
 * <h2>建表命名规则</h2>
 * <ul>
 *   <li>超级表名: product_property_{productKey}（小写）</li>
 *   <li>TAG: device_id NCHAR(50) — 用于区分不同设备的子表</li>
 *   <li>子表名: device_property_{deviceId} — 自动创建，无需手动管理</li>
 * </ul>
 *
 * <h2>改表逻辑（updateThingModel）</h2>
 * <pre>
 * 1. DESCRIBE 查当前表结构 → 获取旧字段列表
 * 2. 物模型属性 → FieldParser.parse() → 新字段列表
 * 3. 对比差异:
 *    新增=新有旧无 → ALTER STABLE ADD COLUMN
 *    修改=名称相同但类型/长度变了 → ALTER STABLE MODIFY COLUMN
 *    删除=旧有新无 → ALTER STABLE DROP COLUMN
 * 4. 全部删除 → DROP STABLE
 * </pre>
 *
 * @see FieldParser 属性→字段转换器
 * @see TableManager SQL生成器
 * @see TdRestApi TDengine REST API 调用
 */
@Slf4j
@Service
public class DbStructureDataImpl implements IDbStructureData {

    @Autowired
    private TdRestApi tdRestApi;

    @Value("${spring.td-datasource.db:eiot}")
    private String dbName;

    // ========================================================================
    // 一、根据物模型创建超级表（首次保存物模型时调用）
    // ========================================================================

    /**
     * 【创建超级表】
     *
     * <p>触发时机：后台首次保存某个产品的物模型。
     *
     * <p>执行流程:
     * <ol>
     *   <li>FieldParser.parse(thingModel) — 物模型属性转数据库字段</li>
     *   <li>TableManager.getCreateSTableSql() — 生成 CREATE STABLE IF NOT EXISTS SQL</li>
     *   <li>TdRestApi.execSql() — HTTP POST 到 TDengine REST API 执行</li>
     * </ol>
     *
     * <p>生成的 SQL 示例:
     * <pre>
     * CREATE STABLE IF NOT EXISTS product_property_abc123 (
     *     time timestamp,
     *     batvoltage FLOAT,
     *     temperature FLOAT
     * ) TAGS (device_id NCHAR(50));
     * </pre>
     *
     * @param thingModel 产品的物模型定义（含属性列表）
     * @throws ServiceException 建表失败时抛出
     */
    @Override
    public void defineThingModel(ThingModel thingModel) {
        // === 步骤 1: 物模型属性 → 数据库字段列表 ===
        // 遍历物模型中所有属性，按 FieldParser 的类型映射规则转为 TDengine 列
        List<TdField> fields = FieldParser.parse(thingModel);
        // 超级表命名: product_property_{productKey}（小写）
        String tbName = Constants.getProductPropertySTableName(thingModel.getProductKey());
        // === 步骤 2: 生成建表 SQL ===
        String sql = TableManager.getCreateSTableSql(tbName,
                fields,
                new TdField("device_id", "NCHAR", 50));  // TAG: 设备ID，用于区分子表
        if (sql == null) {
            return; // 没有属性定义，不建表
        }
        log.info("executing sql:{}", sql);

        // === 步骤 3: 通过 REST API 执行 SQL ===
        TdResponse response = tdRestApi.execSql(sql);
        if (TdResponse.CODE_SUCCESS != response.getCode()) {
            throw exception(TABLE_DEFINE, String.format(
                    "create td stable failed,code:%s,desc:%s"
                    , response.getCode(), response.getDesc()));
        }
    }

    // ========================================================================
    // 二、根据物模型更新超级表结构（修改物模型时调用）
    // ========================================================================

    /**
     * 【更新超级表结构】
     *
     * <p>触发时机：后台修改已有物模型（增删改属性）。
     *
     * <p>核心算法：对比新旧字段差异，生成对应的 ALTER TABLE 语句。
     *
     * <h3>差异检测逻辑</h3>
     * <table>
     *   <tr><th>场景</th><th>判定条件</th><th>SQL 操作</th></tr>
     *   <tr><td>新增属性</td><td>新字段名在旧字段列表中不存在</td>
     *       <td>ALTER STABLE ADD COLUMN</td></tr>
     *   <tr><td>修改属性</td><td>字段名相同但类型或长度不同</td>
     *       <td>ALTER STABLE MODIFY COLUMN</td></tr>
     *   <tr><td>删除属性</td><td>旧字段名（非time/device_id）在新字段列表中不存在</td>
     *       <td>ALTER STABLE DROP COLUMN</td></tr>
     *   <tr><td>全部删除</td><td>新字段列表为空</td>
     *       <td>DROP STABLE IF EXISTS</td></tr>
     * </table>
     *
     * <h3>⚠️ TDengine 限制</h3>
     * MODIFY COLUMN 只能改 NCHAR/BINARY/VARBINARY/GEOMETRY 的长度，
     * 且长度只能增加不能减少。类型变更（如 NCHAR→FLOAT）会报错 code=9803。
     * 遇到这种情况需手动 DROP STABLE 后重新保存。
     *
     * @param thingModel 更新后的物模型定义
     */
    @Override
    public void updateThingModel(ThingModel thingModel) {
        try {
            String tbName = Constants.getProductPropertySTableName(thingModel.getProductKey());

            // === 步骤 1: 查询当前超级表的字段结构 ===
            String sql = TableManager.getDescTableSql(tbName);  // DESCRIBE product_property_{pk}
            TdResponse response = tdRestApi.execSql(sql);
            int code = response.getCode();
            if (code != TdResponse.CODE_SUCCESS) {
                // 表不存在 (code=9731) → 回退到创建逻辑
                if (TdResponse.CODE_TB_NOT_EXIST == code) {
                    defineThingModel(thingModel);
                    return;
                }
                throw exception(TABLE_GET, "get des table error:" + JsonUtils.toJsonString(response));
            }

            // === 步骤 2: 解析新旧字段列表 ===
            // 旧字段：从 DESCRIBE 结果中解析，是当前超级表的实际列
            List<TdField> oldFields = FieldParser.parse(response.getData());
            // 新字段：从物模型属性中解析，是期望的列
            List<TdField> newFields = FieldParser.parse(thingModel);

            // ================================================================
            // 步骤 3: 对比差异，分别处理新增、修改、删除
            // ================================================================

            // --- 3a. 找出【新增】的字段 ---
            // 条件：新字段名在旧字段列表中不存在
            List<TdField> addFields = newFields.stream().filter((f) -> oldFields.stream()
                            .noneMatch(old -> old.getName().equals(f.getName())))
                    .collect(Collectors.toList());
            if (!addFields.isEmpty()) {
                // 生成: ALTER STABLE xxx ADD COLUMN field1 TYPE(length);ALTER STABLE xxx ADD COLUMN field2 TYPE(length);
                sql = TableManager.getAddSTableColumnSql(tbName, addFields);
                response = tdRestApi.execSql(sql);
                if (response.getCode() != TdResponse.CODE_SUCCESS) {
                    throw exception(COLUMN_ADD, "add table column error:" + JsonUtils.toJsonString(response));
                }
            }

            // --- 3b. 找出【修改】的字段 ---
            // 条件：字段名相同，但类型或长度不同
            // 例如：batvoltage NCHAR(1024) → batvoltage FLOAT
            List<TdField> modifyFields = newFields.stream().filter((f) -> oldFields.stream()
                            .anyMatch(old ->
                                    old.getName().equals(f.getName()) // 相同名称
                                            // 类型或长度发生了变化
                                            && (!old.getType().equals(f.getType()) || old.getLength() != f.getLength())
                            ))
                    .collect(Collectors.toList());
            if (!modifyFields.isEmpty()) {
                // 生成: ALTER STABLE xxx MODIFY COLUMN field NEW_TYPE;
                sql = TableManager.getModifySTableColumnSql(tbName, modifyFields);
                response = tdRestApi.execSql(sql);
                if (response.getCode() != TdResponse.CODE_SUCCESS) {
                    // ⚠️ TDengine 限制：MODIFY COLUMN 只能改长度，不能改类型
                    // 常见错误: code=9803 "Only varbinary/binary/nchar/geometry column length could be modified"
                    throw exception(COLUMN_UPDATE, "modify table column error:" + JsonUtils.toJsonString(response));
                }
            }

            // --- 3c. 找出【删除】的字段 ---
            // 条件：旧字段名在新字段列表中不存在
            // 排除 time（系统列）和 device_id（TAG列）
            List<TdField> dropFields = oldFields.stream().filter((f) ->
                            !"time".equals(f.getName()) &&
                                    !"device_id".equals(f.getName()) && newFields.stream()
                                    .noneMatch(n -> n.getName().equals(f.getName())))
                    .collect(Collectors.toList());
            if (newFields.isEmpty()) {
                // 所有属性都删了 → 直接删整个超级表
                sql = TableManager.getDropStableSql(tbName);
                response = tdRestApi.execSql(sql);
                if (response.getCode() != TdResponse.CODE_SUCCESS) {
                    throw exception(TABLE_DELETE, "drop table error:" + JsonUtils.toJsonString(response));
                }
            } else {
                // 只删除部分字段
                if (!dropFields.isEmpty()) {
                    sql = TableManager.getDropSTableColumnSql(tbName, dropFields);
                    response = tdRestApi.execSql(sql);
                    if (response.getCode() != TdResponse.CODE_SUCCESS) {
                        throw exception(COLUMN_DEL, "drop table column error:" + JsonUtils.toJsonString(response));
                    }
                }
            }
        } catch (Throwable e) {
            throw e;
        }
    }

    // ========================================================================
    // 三、应用启动时初始化全局数据库结构
    // ========================================================================

    /**
     * 【初始化全局数据结构】— 应用启动时自动执行
     *
     * <p>创建与物模型无关的全局超级表：
     * <ul>
     *   <li>数据库: CREATE DATABASE IF NOT EXISTS（默认保留365天）</li>
     *   <li>rule_log: 规则执行日志</li>
     *   <li>task_log: 定时任务日志</li>
     *   <li>thing_model_message: 设备上报消息原始日志（所有设备共用）</li>
     *   <li>virtual_device_log: 虚拟设备日志</li>
     * </ul>
     *
     * <p>这些超级表在应用启动时创建一次，后续由 ThingModelService 按产品创建属性超级表。
     */
    @Override
    @PostConstruct
    public void initDbStructure() {
        log.info("td init db structure start");

        // === 创建数据库（如果不存在）===
        // KEEP 365: 数据保留365天
        // DURATION 10: 每个数据文件10天
        // BUFFER 16: 缓冲区大小16MB
        tdRestApi.execSql(String.format(
                "CREATE DATABASE IF NOT EXISTS %s KEEP 365 DURATION 10 BUFFER 16 WAL_LEVEL 1;", dbName));

        // === 创建规则日志超级表 ===
        String sql = TableManager.getCreateSTableSql("rule_log", Arrays.asList(
                new TdField("state1", "NCHAR", 32),
                new TdField("content", "NCHAR", 1024),
                new TdField("success", "BOOL", -1)
        ), new TdField("rule_id", "NCHAR", 50));
        TdResponse response = tdRestApi.execSql(sql);
        if (response.getCode() != TdResponse.CODE_SUCCESS) {
            throw exception(TABLE_DEFINE, "create stable rule_log error:" + JsonUtils.toJsonString(response));
        }

        // === 创建任务日志超级表 ===
        sql = TableManager.getCreateSTableSql("task_log", Arrays.asList(
                new TdField("content", "NCHAR", 1024),
                new TdField("success", "BOOL", -1)
        ), new TdField("task_id", "NCHAR", 50));
        response = tdRestApi.execSql(sql);
        if (response.getCode() != TdResponse.CODE_SUCCESS) {
            throw exception(TABLE_DEFINE, "create stable task_log error:" + JsonUtils.toJsonString(response));
        }

        // === 创建物模型消息日志超级表 ===
        // 存所有设备的上报原始消息，不做物模型过滤
        // 每个设备一个子表: thing_model_message_{deviceId}
        sql = TableManager.getCreateSTableSql("thing_model_message", Arrays.asList(
                new TdField("mid", "NCHAR", 50),           // 消息ID
                new TdField("product_key", "NCHAR", 50),   // 产品Key
                new TdField("device_name", "NCHAR", 50),   // 设备名
                new TdField("uid", "NCHAR", 50),           // 用户ID
                new TdField("type", "NCHAR", 20),          // 消息类型
                new TdField("identifier", "NCHAR", 50),    // 操作标识
                new TdField("code", "INT", -1),            // 状态码
                new TdField("data", "NCHAR", 1024),        // 数据体(JSON)
                new TdField("report_time", "BIGINT", -1)   // 上报时间戳
        ), new TdField("device_id", "NCHAR", 50));         // TAG: 设备ID
        response = tdRestApi.execSql(sql);
        if (response.getCode() != TdResponse.CODE_SUCCESS) {
            throw exception(TABLE_DEFINE, "create stable thing_model_message error:" + JsonUtils.toJsonString(response));
        }

        // === 创建虚拟设备日志超级表 ===
        sql = TableManager.getCreateSTableSql("virtual_device_log", Arrays.asList(
                new TdField("virtual_device_name", "NCHAR", 50),
                new TdField("device_total", "INT", -1),
                new TdField("result", "NCHAR", 1024)
        ), new TdField("virtual_device_id", "NCHAR", 50));
        response = tdRestApi.execSql(sql);
        if (response.getCode() != TdResponse.CODE_SUCCESS) {
            throw exception(TABLE_DEFINE, "create stable virtual_device_log error:" + JsonUtils.toJsonString(response));
        }
        log.info("td init db structure end");
    }
}
