package com.enjoyiot.eiot.temporal.td.service;

import com.enjoyiot.eiot.IDevicePropertyData;
import com.enjoyiot.eiot.temporal.td.config.Constants;
import com.enjoyiot.eiot.temporal.td.dao.TdTemplate;
import com.enjoyiot.eiot.temporal.td.model.TbDeviceProperty;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.DeviceProperty;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【设备属性 — TDengine 时序数据库读写】
 *
 * <h2>职责</h2>
 * 将经过物模型校验的属性值写入 TDengine 时序库，并支持历史数据查询。
 *
 * <h2>存储模型</h2>
 * <pre>
 * 超级表: product_property_{productKey}  ← 定义所有属性列（由物模型建表时创建）
 *   子表: device_property_{deviceId}      ← 每个设备一条 INSERT 即创建子表
 * </pre>
 *
 * <h2>INSERT 关键设计</h2>
 * TDengine 子表在 INSERT 时要求提供<b>所有列</b>的值。
 * 但设备通常不是一次上报所有属性，而是分批上报。
 * 因此每次写入前会：
 * <ol>
 *   <li>从 Redis 读取设备已有的属性缓存（上次上报的值）</li>
 *   <li>用本次上报的新值覆盖旧值（merge）</li>
 *   <li>将所有列的合并值一起写入 TDengine</li>
 * </ol>
 * 这样保证了每次 INSERT 都有完整的列数据。
 *
 * @see DevicePropertyHandler 调用方：负责物模型白名单过滤
 * @see DbStructureDataImpl 建表方：负责创建超级表结构
 */
@Slf4j
@Service
public class DevicePropertyDataImpl implements IDevicePropertyData {

    @Autowired
    private TdTemplate tdTemplate;         // TDengine JDBC 操作封装

    @Resource
    private DeviceApi deviceApi;           // 设备缓存 API（Redis）

    // ========================================================================
    // 一、查询：设备属性历史数据
    // ========================================================================

    /**
     * 【查询设备属性的历史值】
     *
     * <p>从超级表中查询指定时间范围内的设备属性数据。
     * 查询走 TDengine JDBC 连接，不走 REST API。
     *
     * @param deviceId 设备 ID
     * @param name     属性名（物模型 identifier）
     * @param start    开始时间（毫秒）
     * @param end      结束时间（毫秒）
     * @param size     返回条数上限
     * @return 属性历史数据列表
     */
    @Override
    public List<DeviceProperty> findDevicePropertyHistory(Long deviceId, String name,
                                                           long start, long end, int size) {
        // ① 获取设备信息 → 拿到 productKey
        DeviceInfo device = deviceApi.getDeviceInfoFromCache(deviceId);
        if (device == null) {
            return new ArrayList<>();
        }

        // ② 拼查询 SQL: 从超级表 product_property_{pk} 查
        //    嵌套属性（含 "."）用反引号包裹列名
        String tableName = Constants.getProductPropertySTableName(device.getProductKey());
        String fieldName = name.contains(".") ? "`" + name + "`" : name;
        List<TbDeviceProperty> deviceProperties = tdTemplate.query(String.format(
                        "select time,%s as `value`,device_id from %s where device_id=? and time>=? and time<=? order by time asc limit 0,%d",
                        fieldName.toLowerCase(), tableName, size),
                new BeanPropertyRowMapper<>(TbDeviceProperty.class),
                deviceId, start, end);
        // ③ 转换结果
        return deviceProperties.stream().map(property -> new DeviceProperty(
                        property.getTime().toString(),
                        property.getDeviceId(),
                        name,
                        property.getValue(),
                        property.getTime()))
                .collect(Collectors.toList());
    }

    // ========================================================================
    // 二、写入：INSERT 属性到 TDengine
    // ========================================================================

    /**
     * 【写入设备属性到时序库】
     *
     * <p>由 DevicePropertyHandler 调用，数据已通过物模型白名单过滤。
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>从 Redis 获取设备当前属性缓存（上次所有属性的值）</li>
     *   <li>合并本次新值: oldProperties.putAll(newProperties)</li>
     *   <li>构建动态 INSERT SQL:
     *       INSERT INTO device_property_{id} (time, col1, col2, ...)
     *       USING product_property_{pk} TAGS ('{id}')
     *       VALUES (?, ?, ?, ...)</li>
     *   <li>执行 SQL</li>
     * </ol>
     *
     * <h3>合并策略</h3>
     * <pre>
     * 上次缓存: {batVoltage:10.5, temperature:25.0}
     * 本次上报: {batVoltage:10.8}                           ← 只报了 batVoltage
     * 合并结果: {batVoltage:10.8, temperature:25.0}         ← temperature 用旧值补上
     * INSERT 时两列都有值，不会少列
     * </pre>
     *
     * @param deviceId   设备 ID
     * @param properties 本次上报的属性（已过滤 + 归一化）
     * @param time       上报时间戳
     */
    @Override
    public void addProperties(Long deviceId, Map<String, DevicePropertyCache> properties, long time) {
        // ① 获取设备信息（拿 productKey）→ 确定超级表名
        DeviceInfo device = deviceApi.getDeviceInfoFromCache(deviceId);
        if (device == null) {
            return;
        }

        // ② 从 Redis 获取设备已有属性值 → 与本次上报合并
        //    目的: TDengine 子表 INSERT 要求所有列都有值
        //    如果设备 B 只上报了属性 X，但上次还有属性 Y，需要把 Y 的旧值也带上
        Map<String, DevicePropertyCache> oldProperties = deviceApi.getPropertiesFromCache(deviceId);
        oldProperties.putAll(properties);  // 本次新值覆盖旧值

        // ③ 动态拼 SQL
        StringBuilder fieldNames = new StringBuilder();
        StringBuilder fieldPlaceholders = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(time);  // 第一个参数: time 列

        // 遍历所有属性（旧+新），拼列名和占位符
        oldProperties.forEach((key, value) -> {
            // 嵌套属性（含 "."）用反引号包裹
            String fieldName = key.contains(".") ? "`" + key + "`" : key;
            fieldNames.append(fieldName).append(",");
            fieldPlaceholders.append("?,");
            // 值：布尔/字符串 true/false 转 1/0，复杂类型转 JSON 字符串
            args.add(stringifyValue(value.getValue()));
        });
        // 去掉末尾逗号
        fieldNames.deleteCharAt(fieldNames.length() - 1);
        fieldPlaceholders.deleteCharAt(fieldPlaceholders.length() - 1);

        // ④ 拼完整 INSERT SQL
        // 格式: INSERT INTO device_property_{id} (time,col1,col2)
        //       USING product_property_{pk} TAGS ('{id}') VALUES (?,?,?);
        String sql = String.format("INSERT INTO %s (time,%s) USING %s TAGS ('%s') VALUES (?,%s);",
                Constants.getDevicePropertyTableName(deviceId),        // 子表: device_property_{id}
                fieldNames,
                Constants.getProductPropertySTableName(device.getProductKey()),  // 超级表: product_property_{pk}
                deviceId,
                fieldPlaceholders);

        // ⑤ 执行写入
        tdTemplate.update(sql, args.toArray());
    }

    /**
     * 【值 → TDengine 兼容格式】
     *
     * <p>处理逻辑:
     * <ul>
     *   <li>null → null</li>
     *   <li>Boolean true → 1, false → 0</li>
     *   <li>"true"/"false" 字符串 → 1/0</li>
     *   <li>Map/List/数组 → JSON 序列化字符串</li>
     *   <li>其他 → 原值透传</li>
     * </ul>
     */
    private Object stringifyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue ? 1 : 0;
        }
        if (value instanceof String stringValue) {
            if ("true".equalsIgnoreCase(stringValue)) return 1;
            if ("false".equalsIgnoreCase(stringValue)) return 0;
        }
        // 复杂类型 → JSON 字符串
        if (value instanceof Map || value instanceof List || value.getClass().isArray()) {
            return JsonUtils.toJsonString(value);
        }
        return value;
    }
}
