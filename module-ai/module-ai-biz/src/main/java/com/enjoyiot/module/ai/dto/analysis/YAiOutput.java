package com.enjoyiot.module.ai.dto.analysis;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 分析输出（DeepSeek 返回的结构化 JSON）
 *
 * 提示词要求 AI 严格按此结构输出，用于首页各模块展示与塘口画像沉淀。
 *
 * 说明：本实体类【只有实体类、没有对应数据表】，
 * AI 返回内容解析映射到本实体后，先调用 {@link #validate()} 校验结果是否正确，
 * 校验不通过则当前塘口本次分析视为失败（由 analyzeAllPond 兜底跳过，不落库）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class YAiOutput {

    private static final Set<String> LEVEL_SET = Set.of("low", "mid", "high");
    private static final Set<String> EXECUTED_SET = Set.of("yes", "no", "partial");
    private static final Set<String> EFFECTIVE_SET = Set.of("excellent", "good", "fair", "poor");
    private static final Set<String> CONCLUSION_CLS_SET = Set.of("ok", "warn", "danger");
    private static final Set<String> STATUS_CLS_SET = Set.of("good", "warn", "danger");
    private static final Set<String> DATA_CLS_SET = Set.of("good", "warn", "bad");
    private static final Set<String> REVIEW_STATUS_SET = Set.of("done", "half", "no");
    private static final Set<String> TAG_TYPE_SET = Set.of("good", "half", "bad");

    /**
     * 昨日复盘（顶层，用于回填昨日记录的整体判断，区别于 homePage.review 的展示明细）
     */
    private Review review;

    /**
     * 塘口画像更新（新规律/新习惯/固定参数调整）
     */
    private PortraitUpdate portraitUpdate;

    /**
     * 建议可信度（0-100）
     */
    private Integer adviceCredibility;

    /**
     * 首页输出（对应 docs/home-page-api.md 契约，AI 直接输出首页各模块所需字段，存库后首页直接读取）
     */
    private HomePage homePage;

    /**
     * 校验 AI 输出是否正确
     *
     * 校验关键字段的取值是否符合提示词约定的枚举/范围；
     * 任一环节不合法抛出 {@link IllegalArgumentException}，由调用方决定当前塘口本次分析失败。
     */
    public void validate() {
        if (adviceCredibility != null && (adviceCredibility < 0 || adviceCredibility > 100)) {
            throw new IllegalArgumentException("adviceCredibility(建议可信度)取值[" + adviceCredibility + "]不在0-100范围");
        }
        if (review != null) {
            review.validate();
        }
        if (portraitUpdate != null) {
            portraitUpdate.validate();
        }
        if (homePage != null) {
            homePage.validate();
        }
    }

    /**
     * 昨日复盘
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Review {
        /**
         * 是否执行：yes-是 / no-否 / partial-部分执行
         */
        private String executed;
        /**
         * 执行效果：excellent-优 / good-良 / fair-一般 / poor-差
         */
        private String effective;
        /**
         * 复盘结论
         */
        private String conclusion;
        /**
         * 复盘分析
         */
        private String reviewAnalysis;

        public void validate() {
            if (notIn(executed, EXECUTED_SET)) {
                throw new IllegalArgumentException("review.executed(是否执行)取值[" + executed + "]非法，应为 yes/no/partial");
            }
            if (notIn(effective, EFFECTIVE_SET)) {
                throw new IllegalArgumentException("review.effective(执行效果)取值[" + effective + "]非法，应为 excellent/good/fair/poor");
            }
        }
    }

    /**
     * 塘口画像更新
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PortraitUpdate {
        /**
         * 新增规律
         */
        private List<RuleItem> rules;
        /**
         * 新增习惯
         */
        private List<HabitItem> habits;
        /**
         * 固定参数调整（Map<字段, 值>，如 {maxLoadKg: 5000, phMin: 7.5}）
         */
        private Map<String, Object> fixedParams;

        public void validate() {
            if (rules != null) {
                for (RuleItem rule : rules) {
                    if (rule != null) {
                        rule.validate();
                    }
                }
            }
            if (habits != null) {
                for (HabitItem habit : habits) {
                    if (habit != null) {
                        habit.validate();
                    }
                }
            }
        }
    }

    /**
     * 规律项
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuleItem {
        /**
         * 规律标题（如：连续3天凌晨溶氧<4mg/L易浮头）
         */
        private String ruleTitle;
        /**
         * 规律内容
         */
        private String ruleContent;
        /**
         * 初始可信度（0-100，可选）
         */
        private Integer credibility;

        public void validate() {
            if (StrUtil.isBlank(ruleTitle)) {
                throw new IllegalArgumentException("portraitUpdate.rules[].ruleTitle(规律标题)不能为空");
            }
            if (credibility != null && (credibility < 0 || credibility > 100)) {
                throw new IllegalArgumentException("portraitUpdate.rules[].credibility(规律可信度)取值[" + credibility + "]不在0-100范围");
            }
        }
    }

    /**
     * 习惯项
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HabitItem {
        /**
         * 习惯标题（如：习惯在傍晚一次性多投喂）
         */
        private String habitTitle;
        /**
         * 习惯描述
         */
        private String habitDetail;
        /**
         * 习惯倾向（偏保守/偏常规/偏激进/不规律）
         */
        private String habitTendency;
        /**
         * 对养殖影响（正面/中性/负面/待观察）
         */
        private String impact;
        /**
         * 影响说明
         */
        private String impactNote;
        /**
         * 初始可信度（0-100，可选，缺省由系统按 70 兜底）
         */
        private Integer credibility;

        public void validate() {
            if (StrUtil.isBlank(habitTitle)) {
                throw new IllegalArgumentException("portraitUpdate.habits[].habitTitle(习惯标题)不能为空");
            }
            if (credibility != null && (credibility < 0 || credibility > 100)) {
                throw new IllegalArgumentException("portraitUpdate.habits[].credibility(习惯可信度)取值[" + credibility + "]不在0-100范围");
            }
        }
    }

    /**
     * 首页输出（对应 docs/home-page-api.md 契约）
     *
     * 说明：
     * - 首页实时数据（feed.data/wq.data 的 val、oxy.chart、oxy.critical、review.rate/level/done、profile）由后端查库实时供值，
     *   AI 只需输出文案/结论/推断（note/infer/cls/segments/tag/cols 等）；
     * - aiConclusions 固定 3 条，顺序：0-饲喂 / 1-溶氧 / 2-水质。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HomePage {

        /**
         * 安全指数（AI 打分）
         */
        private Safety safety;

        /**
         * 今日结论（固定 3 条：饲喂/溶氧/水质）
         */
        private List<AiConclusion> aiConclusions;

        /**
         * 饲喂卡
         */
        private FeedSection feed;

        /**
         * 溶氧卡
         */
        private OxySection oxy;

        /**
         * 水质卡
         */
        private WqSection wq;

        /**
         * 复盘卡
         */
        private ReviewSection review;

        public void validate() {
            if (safety != null) {
                safety.validate();
            }
            if (aiConclusions != null) {
                for (AiConclusion conclusion : aiConclusions) {
                    if (conclusion != null) {
                        conclusion.validate();
                    }
                }
            }
            if (feed != null) {
                feed.validate();
            }
            if (oxy != null) {
                oxy.validate();
            }
            if (wq != null) {
                wq.validate();
            }
            if (review != null) {
                review.validate();
            }
        }
    }

    /**
     * 安全指数
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Safety {
        /**
         * 安全分（0-100，越高越安全）
         */
        private Integer score;
        /**
         * 安全等级：low-低风险 / mid-中风险 / high-高风险
         */
        private String level;

        public void validate() {
            if (score != null && (score < 60 || score > 100)) {
                throw new IllegalArgumentException("homePage.safety.score(安全分)取值[" + score + "]不在60-100范围");
            }
            if (notIn(level, LEVEL_SET)) {
                throw new IllegalArgumentException("homePage.safety.level(安全等级)取值[" + level + "]非法，应为 low/mid/high");
            }
        }
    }

    /**
     * 今日结论
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiConclusion {
        /**
         * 结论等级：ok-正常 / warn-注意 / danger-危险
         */
        private String cls;
        /**
         * 富文本片段（支持 strong 加粗）
         */
        private List<Segment> segments;

        public void validate() {
            if (notIn(cls, CONCLUSION_CLS_SET)) {
                throw new IllegalArgumentException("homePage.aiConclusions[].cls(结论等级)取值[" + cls + "]非法，应为 ok/warn/danger");
            }
            if (segments == null || segments.isEmpty()) {
                throw new IllegalArgumentException("homePage.aiConclusions[].segments(结论内容)不能为空");
            }
        }
    }

    /**
     * 富文本片段
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Segment {
        /**
         * 文本内容
         */
        private String text;
        /**
         * 是否加粗
         */
        private Boolean strong;
    }

    /**
     * 饲喂卡
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeedSection {
        /**
         * 较昨日投喂量增减%（AI 输出，缺省由后端按今日/昨日建议投喂量计算）
         */
        private Integer adjust;
        /**
         * 指标数据（建议 3 项：水温/溶氧谷值/氨氮；val 由后端实时供值，AI 只需输出 label/note/infer/cls）
         */
        private List<FeedData> data;
        /**
         * AI 建议
         */
        private AiSuggestion ai;

        public void validate() {
            if (data != null) {
                for (FeedData item : data) {
                    if (item != null) {
                        item.validate();
                    }
                }
            }
        }
    }

    /**
     * 饲喂指标数据（AI 在分析过程中总结的昨日指标，指标自选、数量不限，只要符合格式即可，不限于水温/溶氧/氨氮）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeedData {
        /**
         * 指标名（如 水温/溶氧谷值/氨氮，AI 自选）
         */
        private String label;
        /**
         * 数值（昨日数据，AI 分析总结输出，如 28.5）
         */
        private String val;
        /**
         * 单位（如 ℃/mg/L，无单位可为空串）
         */
        private String unit;
        /**
         * 状态说明
         */
        private String note;
        /**
         * 对投喂的推断
         */
        private String infer;
        /**
         * 状态：good-良好 / warn-注意 / bad-偏差
         */
        private String cls;

        public void validate() {
            if (notIn(cls, DATA_CLS_SET)) {
                throw new IllegalArgumentException("homePage.feed.data[].cls(指标状态)取值[" + cls + "]非法，应为 good/warn/bad");
            }
        }
    }

    /**
     * AI 建议（summary 概要 + detail 详细，均为富文本片段）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiSuggestion {
        /**
         * 概要（1-2 句，加粗数字等）
         */
        private List<Segment> summary;
        /**
         * 详细（完整说明）
         */
        private List<Segment> detail;
    }

    /**
     * 溶氧卡
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OxySection {
        /**
         * 状态标签（如 状态良好/临界状态/缺氧风险）
         */
        private Tag tag;
        /**
         * 参考指标（数量不限，AI 建议依据的指标，如 临界值/跌破时间/最低溶氧）
         */
        private List<OxyCol> cols;
        /**
         * 增氧计划
         */
        private AeroPlan aeroPlan;
        /**
         * AI 建议
         */
        private AiSuggestion ai;

        public void validate() {
            if (cols != null) {
                for (OxyCol col : cols) {
                    if (col != null) {
                        col.validate();
                    }
                }
            }
        }
    }

    /**
     * 溶氧参考指标
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OxyCol {
        /**
         * 指标名（如 临界值/跌破时间/最低溶氧）
         */
        private String label;
        /**
         * 数值文本（如 4.0/无/5.4）
         */
        private String val;
        /**
         * 单位
         */
        private String unit;
        /**
         * 状态：good-正常 / warn-注意 / danger-危险
         */
        private String cls;

        public void validate() {
            if (notIn(cls, STATUS_CLS_SET)) {
                throw new IllegalArgumentException("homePage.oxy.cols[].cls(指标状态)取值[" + cls + "]非法，应为 good/warn/danger");
            }
        }
    }

    /**
     * 状态标签
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tag {
        /**
         * 标签文字
         */
        private String text;
        /**
         * 等级：good-正常 / warn-注意 / danger-危险
         */
        private String cls;

        public void validate() {
            if (notIn(cls, STATUS_CLS_SET)) {
                throw new IllegalArgumentException("homePage.tag.cls(标签等级)取值[" + cls + "]非法，应为 good/warn/danger");
            }
        }
    }

    /**
     * 增氧计划
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AeroPlan {
        /**
         * 开始时间（如 22:00，无需增氧时为 —）
         */
        private String start;
        /**
         * 结束时间（如 06:00）
         */
        private String end;
        /**
         * 时长描述（如 8小时/无需增氧）
         */
        private String hours;
    }

    /**
     * 水质卡
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WqSection {
        /**
         * 状态标签（如 水质良好/水质一般/水质较差）
         */
        private Tag tag;
        /**
         * 指标数据（固定 4 项：pH/溶氧/水温/氨氮；val 由后端实时供值，AI 只需输出 label/note/infer/cls）
         */
        private List<WqData> data;
        /**
         * AI 建议
         */
        private AiSuggestion ai;

        public void validate() {
            if (data != null) {
                for (WqData item : data) {
                    if (item != null) {
                        item.validate();
                    }
                }
            }
        }
    }

    /**
     * 水质指标数据（AI 在分析过程中总结的昨日指标，指标自选、数量不限，只要符合格式即可，不限于 pH/溶氧/水温/氨氮）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WqData {
        /**
         * 指标名（如 pH/溶氧/水温/氨氮，AI 自选）
         */
        private String label;
        /**
         * 数值（昨日数据，AI 分析总结输出，如 8.0）
         */
        private String val;
        /**
         * 单位（如 mg/L，无单位可为空串）
         */
        private String unit;
        /**
         * 状态说明
         */
        private String note;
        /**
         * 对水质的推断
         */
        private String infer;
        /**
         * 状态：good-良好 / warn-注意 / bad-偏差
         */
        private String cls;

        public void validate() {
            if (notIn(cls, DATA_CLS_SET)) {
                throw new IllegalArgumentException("homePage.wq.data[].cls(指标状态)取值[" + cls + "]非法，应为 good/warn/bad");
            }
        }
    }

    /**
     * 复盘卡
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewSection {
        /**
         * 昨日建议执行明细（数量与昨日建议条数一致；rate/level/done 由后端计算）
         */
        private List<ReviewItem> items;
        /**
         * AI 建议（复盘总结）
         */
        private AiSuggestion ai;

        public void validate() {
            if (items != null) {
                for (ReviewItem item : items) {
                    if (item != null) {
                        item.validate();
                    }
                }
            }
        }
    }

    /**
     * 复盘明细项
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewItem {
        /**
         * 动作名（如 增氧机/晚间减料）
         */
        private String name;
        /**
         * 状态：done-已执行 / half-部分执行 / no-未执行
         */
        private String status;
        /**
         * 影响结果（如 溶氧 3.2→4.2）
         */
        private String impact;
        /**
         * 结果标签（如 有效/未达标/偏高）
         */
        private String tag;
        /**
         * 标签类型：good-正向 / half-一般 / bad-负向
         */
        private String tagType;

        public void validate() {
            if (notIn(status, REVIEW_STATUS_SET)) {
                throw new IllegalArgumentException("homePage.review.items[].status(执行状态)取值[" + status + "]非法，应为 done/half/no");
            }
            if (notIn(tagType, TAG_TYPE_SET)) {
                throw new IllegalArgumentException("homePage.review.items[].tagType(标签类型)取值[" + tagType + "]非法，应为 good/half/bad");
            }
        }
    }

    /**
     * 判断值非空且不在允许集合内
     */
    private static boolean notIn(String value, Set<String> allowed) {
        return value != null && !allowed.contains(value);
    }

}
