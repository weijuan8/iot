package com.enjoyiot.eiot.temporal.td.dm;

import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【物模型属性 → TDengine 数据库字段】转换器
 *
 * <h2>职责</h2>
 * 将 ThingModel 中定义的属性转换为 TDengine 超级表的列定义。
 * 这是连接"物模型系统"和"时序数据库"的核心桥梁。
 *
 * <h2>类型映射规则</h2>
 * 物模型中的数据类型对应 TDengine 的列类型：
 *
 * <pre>
 * ┌──────────────────┬──────────────────┬─────────────────────────────┐
 * │ 物模型 type       │ TDengine 类型     │ 说明                         │
 * ├──────────────────┼──────────────────┼─────────────────────────────┤
 * │ int / int32      │ INT              │ 32 位整数                    │
 * │ long / int64     │ BIGINT           │ 64 位整数                    │
 * │ float            │ FLOAT            │ 32 位浮点                    │
 * │ decimal / double │ DOUBLE           │ 64 位浮点                    │
 * │ bool / enum      │ TINYINT          │ 布尔/枚举存为小整数           │
 * │ string / text    │ NCHAR(length)    │ 字符串，默认 1024 字符         │
 * │ date / datetime  │ NCHAR            │ 时间类型序列化为 JSON         │
 * │ array / object   │ NCHAR            │ 复杂类型序列化为 JSON 文本    │
 * │ position         │ NCHAR            │ 位置信息序列化为 JSON         │
 * └──────────────────┴──────────────────┴─────────────────────────────┘
 * </pre>
 *
 * <h2>字段命名规则</h2>
 * <ul>
 *   <li>属性 identifier 转为小写作为列名</li>
 *   <li>包含 "." 的嵌套属性名用反引号包裹（如 `location.lat`）</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ol>
 *   <li>创建超级表时: FieldParser.parse(thingModel) → 获取所有字段定义</li>
 *   <li>修改超级表时:
 *       先 DESCRIBE 获取旧字段 → FieldParser.parse(response) → 与新的对比差异</li>
 * </ol>
 *
 * @see DbStructureDataImpl 调用方：建表/改表
 * @see ThingModel 物模型数据结构
 * @see TdField 字段定义对象
 */
public class FieldParser {

    /**
     * 【核心映射表】物模型 type → TDengine 列类型
     *
     * <p>非结构化类型（array/object/position/date/datetime）全部映射为 NCHAR，
     * 在写入时通过 JsonUtils.toJsonString() 序列化。
     */
    private static final Map<String, String> TYPE_MAPPING = Collections.unmodifiableMap(new HashMap<String, String>() {{
        put("int", "INT");
        put("int32", "INT");
        put("long", "BIGINT");
        put("int64", "BIGINT");
        put("float", "FLOAT");
        put("double", "DOUBLE");
        put("bool", "TINYINT");
        put("enum", "TINYINT");
        put("string", "NCHAR");
        put("text", "NCHAR");
        put("date", "NCHAR");
        put("datetime", "NCHAR");
        put("array", "NCHAR");
        put("object", "NCHAR");
        put("position", "NCHAR");
    }});

    /**
     * 【单个属性 → 单个字段】
     *
     * <p>处理流程：
     * <ol>
     *   <li>identifier 转小写作为列名（如 batVoltage → batvoltage）</li>
     *   <li>查 TYPE_MAPPING 获取 TDengine 类型</li>
     *   <li>NCHAR 类型默认长度 1024，可通过 specs.length 自定义</li>
     *   <li>包含 "." 的字段名用反引号包裹</li>
     * </ol>
     *
     * @param property 物模型属性定义
     * @return TdField 数据库字段定义，可直接用于建表 SQL
     */
    public static TdField parse(ThingModel.Property property) {
        // ① 列名 = 属性标识符小写
        String fieldName = property.getIdentifier().toLowerCase();
        // ② 类型映射：物模型 type → TDengine 列类型
        String fieldType = TYPE_MAPPING.get(property.getDataType().normalizedType());
        // ③ 长度：非 NCHAR 类型 length=-1 表示不指定长度
        int length = -1;
        Object rawLength = property.getDataType().getSpecMap().get("length");
        if (rawLength != null) {
            length = Integer.parseInt(String.valueOf(rawLength));
        }
        // NCHAR 类型默认 1024 字符
        if ("NCHAR".equals(fieldType) && length < 1) {
            length = 1024;
        }
        // ④ 嵌套字段（含 "."）用反引号包裹
        if (fieldName.contains(".")) {
            fieldName = "`" + fieldName + "`";
        }
        return new TdField(fieldName, fieldType, length);
    }

    /**
     * 【物模型所有属性 → 字段列表】
     * 用于首次建表时生成 CREATE STABLE 的所有列定义
     */
    public static List<TdField> parse(ThingModel thingModel) {
        return thingModel.getModel().getProperties().stream()
                .map(FieldParser::parse)
                .collect(Collectors.toList());
    }

    /**
     * 【TDengine DESCRIBE 返回结果 → 字段列表】
     *
     * <p>用于更新物模型时对比新旧表结构差异。
     * DESCRIBE 返回的每行数据格式：[fieldName, type, length, note]
     *
     * @param rows DESCRIBE SQL 返回的数据行
     * @return 当前超级表的字段列表
     */
    public static List<TdField> parse(List rows) {
        return (List<TdField>) rows.stream().map((rowValue) -> {
            List row = (List) rowValue;
            String type = row.get(1).toString().toUpperCase();
            return new TdField(
                    row.get(0).toString(),                               // 字段名
                    type,                                                 // 字段类型
                    type.equals("NCHAR")                                  // NCHAR 需要长度
                            ? Integer.parseInt(row.get(2).toString())
                            : -1);                                       // 非 NCHAR 不需要长度
        }).collect(Collectors.toList());
    }

    /**
     * 【字段定义 → SQL 片段】
     *
     * <p>输出格式：
     * <ul>
     *   <li>有长度: "fieldname TYPE(1024)"</li>
     *   <li>无长度: "fieldname TYPE"</li>
     * </ul>
     */
    public static String getFieldDefine(TdField field) {
        return field.getName() + " " + (field.getLength() > 0
                ? String.format("%s(%d)", field.getType(), field.getLength())
                : field.getType());
    }
}
