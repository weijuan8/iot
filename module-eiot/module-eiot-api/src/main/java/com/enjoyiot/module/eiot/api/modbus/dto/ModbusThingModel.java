package com.enjoyiot.module.eiot.api.modbus.dto;


import cn.hutool.core.util.ByteUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.enjoyiot.module.eiot.api.TenantModel;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * 【Modbus 物模型】- 定义产品的 Modbus 属性结构
 *
 * 【什么是物模型？】
 *  物模型是对设备"能做什么"的数字化描述，在 Modbus 场景下主要包含：
 *  1. 属性（Property）：设备有哪些可读/可写的寄存器数据
 *  2. 服务（Service）：设备提供的能力（暂未使用）
 *  3. 事件（Event）：设备可触发的事件（暂未使用）
 *
 * ============================================================================
 * 【物模型在 Modbus 场景中的作用】
 *
 *  Modbus 协议只定义了"如何读写寄存器"，但没有定义"寄存器的值代表什么"。
 *  物模型弥补了这个缺失：
 *
 *  例如，一个温度传感器：
 *    寄存器地址 40001：温度值（保持寄存器，2 个寄存器 = 4 字节，float 类型）
 *    寄存器地址 40003：湿度值（保持寄存器，1 个寄存器 = 2 字节，int 类型）
 *
 *  对应的物模型属性定义：
 *    Property {
 *      identifier: "temperature",
 *      regType: "03",        // 保持寄存器
 *      regAddr: 40001,       // 寄存器起始地址
 *      regNum: 2,            // 占 2 个寄存器
 *      dataType: { type: "float", specs: { precision: 1 } },  // 浮点数，保留 1 位小数
 *      sort: "CD AB"         // 字节序（高字节在前）
 *    }
 *
 *  当 readTask 读取到原始字节 [0x42, 0x48, 0x00, 0x00] 后：
 *    → property.parse() 按 "CD AB" 字节序重排
 *    → dataType.parse() 按 float 类型解析
 *    → 得到业务值：50.0（摄氏度）
 *    → report(PropertyReport) 上报到平台
 *
 * ============================================================================
 * 【物模型的存储与加载】
 *
 *  - 存储：eiot_modbus_thing_model 表（model 字段为 JSON）
 *  - 加载：ModbusThingModelApi.findByProductKey(productKey)
 *  - 缓存：ModbusComponent.pkToThingModel（ConcurrentHashMap）
 *
 * ============================================================================
 * 【Property 的完整字段】
 *
 *  ┌─────────────┬──────────┬──────────────────────────────────────────────┐
 *  │ 字段         │ 示例      │ 说明                                          │
 *  ├─────────────┼──────────┼──────────────────────────────────────────────┤
 *  │ identifier  │ "temp"   │ 属性标识符（唯一，用于属性上报）                │
 *  │ name        │ "温度"    │ 属性显示名称                                   │
 *  │ regType     │ "03"     │ 寄存器类型：01=线圈, 02=离散输入,               │
 *  │             │          │             03=保持寄存器, 04=输入寄存器        │
 *  │ regAddr     │ 40001    │ 寄存器起始地址                                 │
 *  │ regNum      │ 2        │ 寄存器数量（1个寄存器=2字节）                    │
 *  │ sort        │ "CD AB"  │ 字节序（AB/BA/AB CD/CD AB/DC BA/BA DC）        │
 *  │ dataType    │ {...}    │ 数据类型定义（type + specs）                    │
 *  │ processor   │ "x*0.1"  │ 处理公式（可选，对解析后的值做数学变换）         │
 *  │ unit        │ "℃"      │ 单位                                          │
 *  │ accessMode  │ "rw"     │ 访问模式：r=只读, rw=可读写                     │
 *  └─────────────┴──────────┴──────────────────────────────────────────────┘
 *
 * @see Property#parse(byte[]) 字节数据解析核心方法
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModbusThingModel extends TenantModel {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String productKey;

    private ModbusThingModel.Model model;

    public ModbusThingModel(String productKey) {
        this.productKey = productKey;
    }

    @Data
    public static class Model {
        private List<Property> properties;
        private List<ModbusThingModel.Service> services;
        private List<ModbusThingModel.Event> events;

        public Map<String, Service> serviceMap() {
            if (services == null) {
                return new HashMap<>();
            }
            return services.stream().collect(Collectors.toMap(ModbusThingModel.Service::getIdentifier, s -> s));
        }
    }

    /**
     * ========================================================================
     * 【Modbus 物模型属性定义】- 描述一个 Modbus 寄存器数据项
     *
     * 每个 Property 对应一个 Modbus 寄存器的读取定义。
     * readTask 遍历物模型中的所有 Property，为每个 Property 发送一条 Modbus 读取命令。
     *
     * ========================================================================
     * 【parse() 方法 - 字节数据 → 业务值转换核心】
     *
     * 这是整个 Modbus 数据解析链的最后一环，负责将原始寄存器字节转为业务含义的值。
     *
     * 执行流程：
     *  1. 根据 sort（字节序）重排字节
     *     - AB：不重排（原始顺序）
     *     - BA：2 字节内高低字节交换
     *     - CD AB：4 字节内高低字交换（大端→小端）
     *     - DC BA：4 字节内完全反转
     *
     *  2. 调用 dataType.parse(sortedBytes) 根据数据类型解析：
     *     - bool：0/1 → "false"/"true"（可自定义映射）
     *     - enum：数值 → 枚举文本
     *     - int/int32：字节 → 整数
     *     - float：字节 → 浮点数（IEEE 754）
     *     - date：ASCII 时间戳 → Date
     *     - text：字节 → 字符串
     *
     * ========================================================================
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Property {
        public static final String[] SORT_ENUM = {"AB", "BA", "AB CD", "CD AB", "DC BA", "BA DC"};
        private String name;
        private String identifier;
        // 描述
        private String description;
        /**
         * 寄存器类型
         * [{value:'01',label:'线圈状态(RW)',},
         * {value:'02',label:'离散输入(RO)',},
         * {value:'03',label:'保持寄存器(RW)',},
         * {value:'04',label:'输入寄存器(RO)',},]
         */
        private String regType;
        //寄存器地址
        private Integer regAddr;
        //寄存器数量
        private Integer regNum;
        //  * 处理公式
        private String processor;
        //数据顺序
        /**
         * [
         * { value: 'AB',  },
         * { value: 'BA',  },
         * { value: 'AB CD',  },
         * { value: 'CD AB',  },
         * { value: 'DC BA',  },
         * { value: 'BA DC',  },
         * ]
         */
        private String sort;

        private ModbusThingModel.DataType dataType;

        private String accessMode = "rw";

        // 单位
        private String unit;

        /**
         * 【核心方法：将 Modbus 寄存器原始字节解析为业务值】
         *
         * 解析分两步：
         *  1. 字节序重排（sort）：不同设备厂商可能使用不同的字节序
         *  2. 数据类型解析（dataType）：按 bool/int/float/text 等类型转换
         *
         * @param bytes Modbus 寄存器返回的原始字节（已跳过 FunctionCode + ByteCount）
         * @return 解析后的业务值
         */
        public Object parse(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }

            // ========== 步骤 1：字节序重排 ==========
            // 确定排序规则，去除空格（如 "AB CD" → "ABCD"）
            String sort1 = "AB"; // 默认 AB 顺序
            if (Arrays.stream(SORT_ENUM).anyMatch(s -> s.equals(sort))){
                sort1 = sort.replace(" ", "");
            }

            byte[] sortedBytes = new byte[bytes.length];

            // AB 或 ABCD 顺序：不重排，直接使用原始顺序
            if (sort1.equals("ABCD") || sort1.equals("AB")) {
                sortedBytes = bytes;
            } else {
                // 其他顺序：按指定规则重排
                // 将 sort1 中每个字符（A/B/C/D）映射到原始位置
                // 例如 "CDAB"：C→位置0, D→位置1, A→位置2, B→位置3
                int[] index = new int[sort1.length()];
                for (int i = 0; i < sort1.length(); i++) {
                    index[i] = sort1.indexOf(i + 'A'); // 'A'→0, 'B'→1, 'C'→2, 'D'→3
                }

                int len = bytes.length / index.length;
                int remainder = bytes.length % index.length;

                // 字节数必须能整除排序字符数，否则按原样处理
                if (remainder != 0 || len == 0) {
                    sortedBytes = bytes;
                } else {
                    // 按组重排：每组 index.length 个字节，按 index[] 顺序重组
                    // 例如：bytes=[0x01,0x02,0x03,0x04], sort1="CDAB", index=[2,3,0,1]
                    //   结果：[0x03,0x04,0x01,0x02]
                    for (int i = 0; i < len; i++) {
                        int start = i * index.length;
                        for (int j = 0; j < index.length; j++) {
                            sortedBytes[start + j] = bytes[start + index[j]];
                        }
                    }
                }
            }

            // ========== 步骤 2：数据类型解析 ==========
            // dataType.parse() 根据 type 字段将字节转为对应类型：
            //   bool → "true"/"false"
            //   enum → 枚举文本
            //   int/int32 → 整数
            //   float → 浮点数
            //   text → 字符串
            return dataType.parse(sortedBytes);
        }
    }


    @Data
    public static class ProData {
        /**
         * 寄存器类型
         * [{value:'01',label:'线圈状态(RW)',},
         * {value:'02',label:'离散输入(RO)',},
         * {value:'03',label:'保持寄存器(RW)',},
         * {value:'04',label:'输入寄存器(RO)',},]
         */
        private String regType;
        //寄存器地址
        private Integer regAddr;
        //寄存器数量
        private Integer regNum;
        //  * 处理公式
        private String processor;
        //数据顺序
        /**
         * [
         * { value: 'AB',  },
         * { value: 'BA',  },
         * { value: 'AB CD',  },
         * { value: 'CD AB',  },
         * { value: 'DC BA',  },
         * { value: 'BA DC',  },
         * ]
         */
        private String sort;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parameter {
        private String identifier;
        private ThingModel.DataType dataType;
        private String name;
        private Boolean required = false;
    }

    @Data
    public static class Service {
        private String identifier;
        private List<ModbusThingModel.Parameter> inputData;
        private List<ModbusThingModel.Parameter> outputData;
        private String name;
    }

    @Data
    public static class Event {
        private String identifier;
        private List<ModbusThingModel.Parameter> outputData;
        private String name;
    }

    /**
     * ========================================================================
     * 【数据类型定义】- 定义如何将原始字节解析为业务值
     *
     * 支持的数据类型：
     *
     *  ┌──────────┬──────────────────────────────────────────────────────┐
     *  │ type     │ 说明                                                  │
     *  ├──────────┼──────────────────────────────────────────────────────┤
     *  │ bool     │ 布尔值，0→"false", 1→"true"（可自定义映射）          │
     *  │ enum     │ 枚举值，数值→枚举文本                                │
     *  │ int      │ 16 位整数（2 字节）                                  │
     *  │ int32    │ 32 位整数（4 字节）                                  │
     *  │ float    │ 32 位 IEEE 754 浮点数（4 字节）                      │
     *  │ date     │ ASCII 时间戳字符串→Date                              │
     *  │ text     │ ASCII 文本                                           │
     *  │ position │ 位置值（暂按 float 处理）                            │
     *  └──────────┴──────────────────────────────────────────────────────┘
     *
     * specs 字段用于类型特定的参数：
     *  - bool: {"0":"关闭","1":"开启"}  // 自定义映射
     *  - enum: {"0":"停止","1":"运行","2":"故障"}
     *  - float: {"precision":2}  // 保留 2 位小数
     * ========================================================================
     */
    @Data
    public static class DataType {
        private String type;
        private Object specs;

        public Object parse(byte[] bytes) {
            type = type.toLowerCase();
            JSONObject specsMap = specs != null ? JSONUtil.parseObj(specs) : new JSONObject();


            int intValue;
            if (bytes.length >= 4) {
                intValue = ByteUtil.bytesToInt(bytes, ByteOrder.BIG_ENDIAN);
            } else if (bytes.length >= 2) {
                intValue = ByteUtil.bytesToShort(bytes, ByteOrder.BIG_ENDIAN);
            } else {
                intValue = (bytes[0] & 255);
            }

            switch (type) {
                case "bool":
                    String _false = specsMap.getStr("0", "false");
                    String _true = specsMap.getStr("1", "true");
                    return intValue == 0 ? _false : _true;
                case "enum":
                    return specsMap.getStr(String.valueOf(intValue), String.valueOf(intValue));
                case "int":
                case "int32":
                    return intValue;
                case "float":
                    float floatVal = Float.intBitsToFloat(intValue);
                    // 保留小数位数
                    Integer precision = specsMap.getInt("precision", -1);
                    if (NumberUtil.isValid(floatVal)) {
                        if (precision >= 0) {
                            BigDecimal bigDecimal = NumberUtil.roundHalfEven(floatVal, precision);
                            return bigDecimal.floatValue();
                        } else {
                            return floatVal;
                        }
                    }
                    return null;
                case "date":
                    // String类型的UTC时间戳（毫秒）
                    String dateStr = new String(bytes, StandardCharsets.US_ASCII);
                    if (NumberUtil.isLong(dateStr)) {
                        return new Date(Long.parseLong(dateStr));
                    }
                    return null;
                case "text":
                    return new String(bytes, StandardCharsets.US_ASCII);
                case "position":
                    // TODO: 还未规定具体的格式，先按照浮点数处理
                    float position = Float.intBitsToFloat(intValue);
                    if (NumberUtil.isValid(position)) {
                        return position;
                    }
                    return null;
                default:
                    return new String(bytes, StandardCharsets.US_ASCII);
            }

        }
    }
}
