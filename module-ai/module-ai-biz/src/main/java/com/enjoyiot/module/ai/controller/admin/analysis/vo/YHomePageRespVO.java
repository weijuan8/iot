package com.enjoyiot.module.ai.controller.admin.analysis.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 首页数据拼接 VO（对应 docs/home-page-api.md 契约）
 *
 * 由 y_daily_analysis_result 主表 + y_ai_analysis_output 明细 + 设备时序数据 + 塘口画像 拼接而成，
 * 前端按此结构即可完整渲染首页全部卡片。
 *
 * 数据来源约定：
 * - AI 输出的文案/结论/推断（note/infer/cls/segments/tag/cols/safety 等）读自 analysis_output 中 homePage 结构；
 * - 实时数值（feed.data/wq.data 的 val、oxy.chart、oxy.critical、review.rate/level/done、profile）由后端查库/计算实时供值。
 */
@Data
public class YHomePageRespVO {

    /**
     * 本次分析时间（序列化为毫秒时间戳；首页所用最新一条分析记录的创建时间，即 AI 分析落库时刻）
     */
    private LocalDateTime analysisTime;

    /**
     * 安全指数（AI 打分 + 等级）
     */
    private SafetyVO safety;

    /**
     * 今日结论列表（固定 3 条，依次对应 饲喂/溶氧/水质）
     */
    private List<AiConclusionVO> aiConclusions;

    /**
     * 今日饲喂建议卡
     */
    private FeedVO feed;

    /**
     * 溶氧预警卡
     */
    private OxyVO oxy;

    /**
     * 今日水质研判卡
     */
    private WqVO wq;

    /**
     * 昨日建议复盘卡
     */
    private ReviewVO review;

    /**
     * 塘口画像卡
     */
    private ProfileVO profile;

    // ========== 通用 ==========

    /**
     * 富文本片段（segments[] / summary[] / detail[] 通用）
     */
    @Data
    public static class SegmentVO {
        /**
         * 一段文字
         */
        private String text;
        /**
         * true → 前端加粗 + 主题色高亮
         */
        private Boolean strong;
    }

    /**
     * 标签（卡片标题右侧）
     */
    @Data
    public static class TagVO {
        /**
         * 标签文案
         */
        private String text;
        /**
         * 状态：good / warn / danger
         */
        private String cls;
    }

    /**
     * AI 建议框（summary 概要 + detail 详细，均为富文本片段）
     */
    @Data
    public static class AiSuggestionVO {
        /**
         * 概要（1-2 句）
         */
        private List<SegmentVO> summary;
        /**
         * 详细（完整说明）
         */
        private List<SegmentVO> detail;
    }

    // ========== safety ==========

    @Data
    public static class SafetyVO {
        /**
         * 安全分（0-100 整数，分值越低越危险）
         */
        private Integer score;
        /**
         * 安全等级：low / mid / high
         */
        private String level;
    }

    // ========== aiConclusions ==========

    @Data
    public static class AiConclusionVO {
        /**
         * 状态：ok=正常 / warn=预警 / danger=危险
         */
        private String cls;
        /**
         * 富文本片段
         */
        private List<SegmentVO> segments;
    }

    // ========== feed ==========

    @Data
    public static class FeedDataVO {
        /**
         * 指标名，如 水温 / 溶氧谷值 / 氨氮
         */
        private String label;
        /**
         * 数值文本（数据库实时供值）
         */
        private String val;
        /**
         * 单位，可为空串
         */
        private String unit;
        /**
         * 指标状态备注（AI 输出）
         */
        private String note;
        /**
         * 对投喂的推论（AI 输出）
         */
        private String infer;
        /**
         * 状态：good / warn / bad
         */
        private String cls;
    }

    @Data
    public static class FeedVO {
        /**
         * 相对昨日投喂量增减百分比（正/负/0）
         */
        private Integer adjust;
        /**
         * 指标卡（建议 3 项：水温 / 溶氧谷值 / 氨氮，数量不限）
         */
        private List<FeedDataVO> data;
        /**
         * 卡片底部「AI 建议框」内容
         */
        private AiSuggestionVO ai;
    }

    // ========== oxy ==========

    @Data
    public static class OxyColVO {
        /**
         * 列名，如 临界值 / 跌破时间 / 最低溶氧（数量不限，AI 建议依据的指标）
         */
        private String label;
        /**
         * 值文本（跌破时间无跌破可传「无」）
         */
        private String val;
        /**
         * 单位，可为空串
         */
        private String unit;
        /**
         * 状态：good / warn / danger
         */
        private String cls;
    }

    @Data
    public static class ChartVO {
        /**
         * 24 小时溶氧序列，必须 25 个点（0 时~24 时）
         */
        private List<Double> data;
        /**
         * 横轴时间刻度，默认 ["00:00","06:00","12:00","18:00","24:00"]
         */
        private List<String> labels;
        /**
         * 曲线 y 轴下限
         */
        private Double min;
        /**
         * 曲线 y 轴上限
         */
        private Double max;
        /**
         * 临界虚线值，应与 oxy.critical 一致
         */
        private Double critical;
    }

    @Data
    public static class AeroPlanVO {
        /**
         * 增氧开始时间 HH:mm；无需增氧传 —
         */
        private String start;
        /**
         * 增氧结束时间 HH:mm；无需增氧传 —
         */
        private String end;
        /**
         * 时长文案，如 5.5 小时；无需增氧传 无需增氧
         */
        private String hours;
    }

    @Data
    public static class OxyVO {
        /**
         * 卡片标题右侧标签
         */
        private TagVO tag;
        /**
         * 溶氧临界值（mg/L）
         */
        private Double critical;
        /**
         * 参考指标列（数量不限，AI 建议依据的指标）
         */
        private List<OxyColVO> cols;
        /**
         * 24h 溶氧曲线
         */
        private ChartVO chart;
        /**
         * 增氧方案
         */
        private AeroPlanVO aeroPlan;
        /**
         * 「AI 建议框」内容
         */
        private AiSuggestionVO ai;
    }

    // ========== wq ==========

    @Data
    public static class WqDataVO {
        /**
         * 指标名：pH / 溶氧 / 水温 / 氨氮
         */
        private String label;
        /**
         * 数值文本（数据库实时供值）
         */
        private String val;
        /**
         * 单位，可为空串
         */
        private String unit;
        /**
         * 状态备注（AI 输出）
         */
        private String note;
        /**
         * 建议动作（AI 输出）
         */
        private String infer;
        /**
         * 状态：good / warn / bad
         */
        private String cls;
    }

    @Data
    public static class WqVO {
        /**
         * 标签
         */
        private TagVO tag;
        /**
         * 指标卡，固定 4 项：pH / 溶氧 / 水温 / 氨氮
         */
        private List<WqDataVO> data;
        /**
         * 「AI 建议框」内容
         */
        private AiSuggestionVO ai;
    }

    // ========== review ==========

    @Data
    public static class ReviewItemVO {
        /**
         * 动作名，如 增氧机 / 晚间减料 / 午后微流水
         */
        private String name;
        /**
         * 状态文字，如 已执行 / 部分执行 / 未执行
         */
        private String state;
        /**
         * 状态：done / half / no
         */
        private String status;
        /**
         * 影响结果，如 溶氧 3.2→4.2
         */
        private String impact;
        /**
         * 结果标签文字，如 有效 / 未达标 / 偏高 / 恶化
         */
        private String tag;
        /**
         * 标签类型：good / half / bad
         */
        private String tagType;
    }

    @Data
    public static class ReviewVO {
        /**
         * 执行率（0-100 整数，done/items.length × 100，系统计算）
         */
        private Integer rate;
        /**
         * 执行率等级：low-执行率高(≥80%) / mid(60-79%) / high-执行率低(<60%)
         */
        private String level;
        /**
         * 已执行条数，须 ≤ items.length
         */
        private Integer done;
        /**
         * 逐条复盘明细
         */
        private List<ReviewItemVO> items;
        /**
         * 「AI 建议框」内容（复盘总结）
         */
        private AiSuggestionVO ai;
    }

    // ========== profile ==========

    @Data
    public static class StatVO {
        /**
         * 数字文本，如 68 / 78%
         */
        private String num;
        /**
         * 统计名，如 分析天数 / 累计建议 / 采纳率 / 沉淀规律
         */
        private String label;
    }

    @Data
    public static class ProfileVO {
        /**
         * 核心统计四宫格，固定 4 项
         */
        private List<StatVO> stats;
    }

}
