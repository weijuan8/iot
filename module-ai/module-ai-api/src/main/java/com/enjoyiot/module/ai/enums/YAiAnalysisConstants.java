package com.enjoyiot.module.ai.enums;

import java.util.List;
import java.util.Map;

/**
 * AI 塘口分析 - 静态参数 / 固定参数 / 状态取值 常量集
 *
 * 集中收纳 module-ai 中散落在各实现类的固定参数与契约状态字符串，
 * 与 {@link ErrorCodeConstants} 同处 enums 固定目录，统一管理。
 */
public interface YAiAnalysisConstants {

    // ========== 分析调度参数 ==========

    /**
     * AI 分析投喂设备时序数据的周期（小时）：只投喂近 24 小时属性序列与开关段，
     * 控制 prompt 数据量；AI 趋势分析仅基于最近 24 小时。
     */
    int DEVICE_SERIES_PERIOD_HOURS = 24;

    /**
     * 巡塘上报摘要查询周期（天）：保留昨日 + 今日共 2 天，
     * 复盘昨日建议需依赖昨日的投喂/换水/浮头上报数据。
     */
    int REPORT_SUMMARY_PERIOD_DAYS = 2;

    /**
     * 首页查询日聚合数据的回退窗口（天）：首页只展示"最新一天"的日统计，
     * 该窗口仅用于设备当天无上报时回退到最近有数据的一天，避免指标显示 "-"；
     * 与 AI 分析的 24 小时周期分开，避免首页高频实时接口查询量放大。
     */
    int HOME_STAT_LOOKBACK_DAYS = 3;

    /**
     * 每日 AI 分析定时任务 cron（每天 06:00）
     */
    String DAILY_ANALYSIS_CRON = "0 0 6 * * ?";

    /**
     * 投喂 AI 的塘口规律查询上限（条）：取可信度最高的前 N 条规律
     */
    int RULE_QUERY_LIMIT = 10;

    /**
     * 投喂 AI 的农户习惯查询上限（条）：取可信度最高的前 N 条习惯，避免习惯无限膨胀稀释 prompt
     */
    int HABIT_QUERY_LIMIT = 10;

    // ========== 安全指数（safety 0-100 整数） ==========

    /** 风险低 → 安全指数 */
    int SAFETY_SCORE_GOOD = 88;
    /** 风险中 / 缺省 → 安全指数 */
    int SAFETY_SCORE_MID = 68;
    /** 风险高 → 安全指数（安全指数下限 60，不设低于 60 的分） */
    int SAFETY_SCORE_BAD = 60;
    /** 兜底可信度上限阈值（≥ 此值 → GOOD） */
    int CREDIBILITY_HIGH = 80;
    /** 兜底可信度下限阈值（≥ 此值 → MID，否则 → BAD） */
    int CREDIBILITY_LOW = 60;

    // ========== 溶氧 / 水质阈值 ==========

    /** 溶氧临界值缺省（mg/L） */
    double DEFAULT_DO_CRITICAL = 4.0;
    /** 氨氮上限缺省（mg/L） */
    double DEFAULT_NH_MAX = 0.5;
    /** 临界注意冗余（mg/L）：最低溶氧 < 临界 + 0.5 → 临界注意 */
    double DO_CRITICAL_MARGIN = 0.5;
    /** 增氧开始时间缺省 */
    String DEFAULT_AERO_START = "22:00";
    /** 增氧结束时间缺省 */
    String DEFAULT_AERO_END = "07:30";

    // ========== 24h 溶氧曲线 ==========

    /** 曲线点数（0 时~24 时，25 个点） */
    int CURVE_POINTS = 25;
    /** 曲线小时桶数 */
    int CURVE_HOURS = 24;
    /** 曲线全部为空时的兜底值（mg/L） */
    double CURVE_DEFAULT_VALUE = 5.0;
    /** 曲线 y 轴下限 */
    double CHART_MIN = 2.0;
    /** 曲线 y 轴上限 */
    double CHART_MAX = 8.0;

    // ========== 首页模块数量约束 ==========

    /** 今日结论条数 */
    int AI_CONCLUSION_COUNT = 3;
    /** 饲喂卡指标列数 */
    int FEED_COL_COUNT = 3;
    /** 水质卡指标列数 */
    int WQ_COL_COUNT = 4;
    /** 画像卡统计项数 */
    int PROFILE_STAT_COUNT = 4;

    // ========== 契约状态字符串（cls / status / tagType） ==========

    /** 状态：正常 */
    String CLS_OK = "ok";
    /** 状态：良好 */
    String CLS_GOOD = "good";
    /** 状态：预警 */
    String CLS_WARN = "warn";
    /** 状态：危险 */
    String CLS_DANGER = "danger";
    /** 状态：超标 */
    String CLS_BAD = "bad";
    /** 状态：已执行 */
    String CLS_DONE = "done";
    /** 状态：部分执行 */
    String CLS_HALF = "half";
    /** 状态：未执行 */
    String CLS_NO = "no";

    // ========== AI 输出枚举值 ==========

    /** 风险等级：低 */
    String RISK_LOW = "low";
    /** 风险等级：中 */
    String RISK_MID = "mid";
    /** 风险等级：高 */
    String RISK_HIGH = "high";
    /** 水质：差 */
    String WQ_POOR = "poor";
    /** 水质：正常 */
    String WQ_NORMAL = "normal";
    /** 执行情况：是 */
    String EXEC_YES = "yes";
    /** 执行情况：部分 */
    String EXEC_PARTIAL = "partial";
    /** 执行效果：优秀 */
    String EFFECT_EXCELLENT = "excellent";
    /** 执行效果：良好 */
    String EFFECT_GOOD = "good";
    /** 执行效果：一般 */
    String EFFECT_FAIR = "fair";
    /** 执行效果：差 */
    String EFFECT_POOR = "poor";

    // ========== 水质/饲喂阈值区间文案（首页卡片 note） ==========

    /** 溶氧适宜下限 */
    String NOTE_DO_SUITABLE = "≥4 mg/L 适宜";
    /** 氨氮适宜上限 */
    String NOTE_NH_SUITABLE = "≤0.5 mg/L 适宜";

    // ========== 单位 ==========

    /** 单位：mg/L */
    String UNIT_MG_L = "mg/L";
    /** 单位：℃ */
    String UNIT_CELSIUS = "℃";

    // ========== 属性 key / 中文名 ==========

    /** 属性 key：溶氧 */
    String PROP_DO = "DO";
    /** 属性 key：pH */
    String PROP_PH = "PH";
    /** 属性 key：水温 */
    String PROP_TEMP = "tem";
    /** 属性 key：氨氮 */
    String PROP_NH = "NH";
    /** 属性中文名：溶氧 */
    String PROP_DO_NAME = "溶氧";
    /** 属性中文名：pH */
    String PROP_PH_NAME = "pH";
    /** 属性中文名：水温 */
    String PROP_TEMP_NAME = "水温";
    /** 属性中文名：氨氮 */
    String PROP_NH_NAME = "氨氮";
    /**增氧开关**/
    String SWITCH_AERATOR1 = "增氧开关";

    // ========== 产品分析参数配置（分析设备白名单） ==========

    /**
     * 塘口分析产品-参数白名单：
     * key = 产品标识 productKey（对应 eiot_product.product_key / DeviceInfo.productKey）
     * value = 该产品参与分析的属性标识符列表（属性标识符大小写敏感，需与设备物模型一致，如 pH/NH/DO/temp）
     *
     * 规则：
     * 1. 设备 productKey 未在此配置 → 设备不参与塘口分析（如太阳能、摄像头等无需分析的设备）；
     * 2. 设备 productKey 已配置 → 仅分析 value 中列出的属性；
     * 3. 已配置但设备当前未上传的属性 → 记入 YPondDeviceData.missingProperties（按 null 语义处理，不报错）。
     *
     * 注意：部署前必须将实际产品 key 填入（eiot_product.product_key），白名单为空时所有设备都不参与分析。
     */
    Map<String, List<String>> POND_ANALYSIS_PRODUCT_PROPERTIES = Map.of(
            // 示例：水质传感器产品，分析 pH、氨氮两个参数
            "apFx3ja3p3nHiSRW", List.of(PROP_PH, PROP_NH, PROP_TEMP, PROP_DO,SWITCH_AERATOR1)
    );

    // ========== DeepSeek 大模型客户端配置 ==========
    // 集中管理，不再使用 application.yaml 的 ai.deepseek 配置块；
    // 生产环境可继续通过环境变量 AI_DEEPSEEK_API_KEY / AI_DEEPSEEK_URL / AI_DEEPSEEK_MODEL 覆盖默认值。

    /** DeepSeek API Key（优先取环境变量 AI_DEEPSEEK_API_KEY，缺省用默认值） */
    String DEEPSEEK_API_KEY = System.getenv().getOrDefault("AI_DEEPSEEK_API_KEY",
            "sk-7774d341af544c4fab8f5f4fc2701fc6");
    /** DeepSeek 接口地址（OpenAI 兼容协议，优先取环境变量 AI_DEEPSEEK_URL） */
    String DEEPSEEK_URL = System.getenv().getOrDefault("AI_DEEPSEEK_URL",
            "https://api.deepseek.com/chat/completions");
    /** DeepSeek 模型名（优先取环境变量 AI_DEEPSEEK_MODEL） */
    String DEEPSEEK_MODEL = System.getenv().getOrDefault("AI_DEEPSEEK_MODEL", "deepseek-chat");
    /** DeepSeek 调用超时时间（秒） */
    int DEEPSEEK_TIMEOUT_SECONDS = 120;

}
