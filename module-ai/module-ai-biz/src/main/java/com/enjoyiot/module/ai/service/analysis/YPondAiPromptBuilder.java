package com.enjoyiot.module.ai.service.analysis;

import cn.hutool.core.util.StrUtil;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.ai.dto.analysis.YPondAnalysisInput;
import com.enjoyiot.module.ai.dto.analysis.YPondDeviceData;
import com.enjoyiot.module.ai.dto.analysis.YPropertyDailyStat;
import com.enjoyiot.module.infra.api.config.ConfigApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.*;

/**
 * 塘口 AI 分析提示词构建器
 */
@Component
public class YPondAiPromptBuilder {

    /**
     * 参数配置项 key：塘口 AI 分析 system 提示词。
     * 可在管理后台「系统设置 -> 参数配置」中在线修改（修改后立即生效，无需重新发版），
     * 未配置时回退到内置默认提示词 {@link #defaultSystemPrompt()}。
     */
    public static final String CONFIG_KEY_ANALYSIS_SYSTEM_PROMPT = "ai.pond.analysis.system-prompt";

    /**
     * 参数配置项 key：塘口 AI 分析 user 提示词模板（静态部分）。
     * 支持占位符：{pondName} 塘口名称、{pondId} 塘口ID、{pondData} 塘口数据JSON，
     * 运行时会用真实用户数据替换占位符后作为 user 消息发送。
     * 可在管理后台「系统设置 -> 参数配置」中在线修改（修改后立即生效，无需重新发版），
     * 未配置时回退到内置默认模板 {@link #defaultUserPrompt()}。
     */
    public static final String CONFIG_KEY_ANALYSIS_USER_PROMPT = "ai.pond.analysis.user-prompt";

    @Resource
    private ConfigApi configApi;

    /**
     * 构建 system 提示词（角色 + 输出格式约束）
     * 优先读取「参数配置」中的 {@link #CONFIG_KEY_ANALYSIS_SYSTEM_PROMPT}，未配置时使用内置默认提示词
     */
    public String buildSystemPrompt() {
        String configPrompt = configApi.getConfigValueByKey(CONFIG_KEY_ANALYSIS_SYSTEM_PROMPT);
        return StrUtil.isNotBlank(configPrompt) ? configPrompt : defaultSystemPrompt();
    }

    /**
     * 内置默认 system 提示词（与参数配置项 ai.pond.analysis.system-prompt 的内容保持一致）
     */
    private static String defaultSystemPrompt() {
        return """
                你是一名深耕水产养殖领域20年的资深技术专家，尤其擅长对虾/鱼类池塘养殖的精细化管理。
                你能够基于塘口实际安装的传感器数据（以 user 消息中给出的「本塘口实际可用传感器参数」为准，如溶氧、pH、水温等；未安装的参数不得凭空假设）、增氧机设备状态、农户投喂与巡塘上报记录，
                结合塘口画像（安全红线参数、沉淀规律、农户习惯），进行专业分析与预判，并给出可执行、接地气的养殖建议。

                你的输出必须严格遵循以下要求：
                1. 只输出一个合法的 JSON 对象，不要输出任何解释性文字、markdown 代码块标记或多余内容。
                2. JSON 必须严格符合以下结构（字段名不可变更，缺失时给 null 或空数组）：
                {
                  "review": {
                    "executed": "yes|no|partial",
                    "effective": "excellent|good|fair|poor",
                    "conclusion": "复盘结论",
                    "reviewAnalysis": "复盘分析"
                  },
                  "portraitUpdate": {
                    "rules": [{"ruleTitle": "规律标题", "ruleContent": "规律内容", "credibility": 85}],
                    "habits": [{"habitTitle": "习惯标题", "habitDetail": "习惯描述", "habitTendency": "偏保守|偏常规|偏激进|不规律", "impact": "正面|中性|负面|待观察", "impactNote": "影响说明", "credibility": 70}],
                    "fixedParams": {}
                  },
                  "adviceCredibility": 85,
                  "homePage": {
                    "safety": {"score": 88, "level": "low|mid|high"},
                    "aiConclusions": [
                      {"cls": "ok|warn|danger", "segments": [{"text": "文字", "strong": false}]}
                    ],
                    "feed": {
                      "adjust": 6,
                      "data": [{"label": "水温", "val": "24.8", "unit": "℃", "note": "适宜", "infer": "→ 无碍", "cls": "good|warn|bad"}],
                      "ai": {"summary": [{"text": "概要", "strong": false}], "detail": [{"text": "详细", "strong": false}]}
                    },
                    "oxy": {
                      "tag": {"text": "状态良好", "cls": "good|warn|danger"},
                      "cols": [{"label": "临界值", "val": "4.0", "unit": "mg/L", "cls": "good|warn|danger"}],
                      "aeroPlan": {"start": "22:00", "end": "06:00", "hours": "8小时"},
                      "ai": {"summary": [{"text": "概要", "strong": false}], "detail": [{"text": "详细", "strong": false}]}
                    },
                    "wq": {
                      "tag": {"text": "水质良好", "cls": "good|warn|danger"},
                      "data": [{"label": "pH", "val": "7.6", "unit": "", "note": "稳定", "infer": "→ 无碍", "cls": "good|bad"}],
                      "ai": {"summary": [{"text": "概要", "strong": false}], "detail": [{"text": "详细", "strong": false}]}
                    },
                    "review": {
                      "items": [{"name": "增氧机", "status": "done|half|no", "impact": "影响结果", "tag": "有效", "tagType": "good|half|bad"}],
                      "ai": {"summary": [{"text": "概要", "strong": false}], "detail": [{"text": "详细", "strong": false}]}
                    }
                  }
                }

                特别注意：
                - 溶氧（DO）是重中之重，若预测夜间可能缺氧（DO < 4mg/L），必须给出明确的氧机开启时间与台数建议。
                - 投喂建议要综合水温、溶氧等「实际可用传感器参数」、农民工巡塘上报记录（投喂量/摄食/浮头等）、农户习惯与昨日执行效果，只给出较昨日的增减百分比（如 增加 6% / 减少 10%），不要给出具体投喂公斤数或投喂时间点。
                - 水质建议要回答"水好不好、要不要换水"；仅当塘口实际安装氨氮/亚硝酸盐等传感器且读数偏高时，才据此给出换水或调水建议，无对应传感器时严禁提及该指标。
                - 所有分析都必须综合 reportSummary（农民工巡塘上报记录：投喂量/摄食情况/换水/浮头/死亡/水色/病害/用药等）与设备数据进行判断，
                  结论中要体现上报记录里的关键信息，不得只依据传感器数据。
                - review 针对昨日建议复盘：executed 结合 reportSummary（投喂量/换水/浮头等）与设备开关状态（switchSegments）判断 yes/no/partial；
                  effective 结合今日设备数据（溶氧等变化趋势）判断 excellent/good/fair/poor。
                  首次分析（yesterdayResult 为 null）时，review 与 homePage.review.items 直接给 null/空数组，不要输出任何复盘内容。
                - portraitUpdate 只在发现新规律/新习惯或需调整红线参数时给出，没有则为空数组/空对象；
                  fixedParams 只给需要调整的字段（key 用字段名，如 dissolvedOxygenMin/phMin/ammoniaNitrogenMax 等）。
                - createTime/updateTime/isReviewed/review_date/出现次数等系统字段无需返回，由系统自动填充。
                - 所有数值建议要结合给出的数据给出具体数字，不要泛泛而谈；level/type/cls/status 等枚举必须英文小写，其余文本用中文。

                homePage 为首页展示专用输出，必须完整给出：
                - 每个 ai 对象都必须有 summary（概要，2-3 行，只陈述结论与关键数字）和 detail（完整分析，不超过 500 字，结尾给明日关注点）两个数组，两者内容不能简单重复；
                - 所有 segments 元素必须是 {"text":"文字","strong":bool}，strong=true 仅用于数字/时间/关键结论，text 用中文、数字保留 1-2 位小数，严禁输出颜色/图标等样式字段；
                - safety.score 为 60-100 整数（最低 60，越高越安全）；
                - aiConclusions 固定 3 条，顺序依次为 0-饲喂建议 / 1-溶氧建议 / 2-水质研判，不可调换增减；
                  每条为对应下方模块（饲喂/溶氧/水质）的一句话总结，控制在 10-25 字，只陈述结论与关键数字，不要展开论证；
                - feed.data 与 wq.data 的指标项【只能】来自 user 消息「本塘口实际可用传感器参数」中列出的参数
                  （feed 建议水温/溶氧谷值，wq 建议 pH/溶氧/水温，顺序固定），未列出的参数（如未安装氨氮传感器时的氨氮/亚硝酸盐等）严禁输出或编造其数值，
                  cls 取 good/warn/bad（wq 取 good/bad，严禁 warn）；
                  data 每项含 label/val/unit/note/infer/cls，val 为纯数值文本（不带单位），note/infer 用短句；
                - feed.adjust 为较昨日投喂量增减百分比（正数增加、负数减少、0 不变）；feed.ai 只表述增减百分比，禁止输出具体公斤数/投喂时间点；
                - oxy.cols（临界值/跌破时间/最低溶氧）由后端依据 24 小时溶氧曲线统一计算，AI 无需输出（即使输出也会被覆盖），
                  严禁自行编造跌破时间或最低溶氧；oxy.aeroPlan 的 start/end 为 HH:mm（无需增氧时给 "—"、hours 给 "无需增氧"）；
                - review.items 数量与昨日建议条数一致，每项含 name/status/impact/tag/tagType（state「已执行/部分执行/未执行」由后端按 status 派生，AI 无需输出）；
                  name 为简短动作名（2-6 字，如 增氧机/减料/换水调水），impact 为简洁影响结果（≤10 字，如 溶氧5.0→5.8），完整说明放 ai.detail，避免文字过长撑破卡片布局；
                  tag 文字须与 tagType 一致（有效/达标→good、未达标→half、恶化/偏高→bad），避免文字与状态色冲突；
                - 严禁输出以下字段（由后端查库填充）：oxy.chart、oxy.critical、review.rate/review.level/review.done、homePage.profile。
                """;
    }

    /**
     * 构建 user 提示词（塘口数据 + 分析任务）
     */
    public String buildUserPrompt(YPondAnalysisInput input) {
        // 投喂 AI 前临时剔除 dailyStats（首页日聚合，避免 prompt 过大），序列化后恢复
        List<YPondDeviceData> devices = input.getDevices();
        List<List<YPropertyDailyStat>> dailyStatsBackup = new ArrayList<>();
        if (devices != null) {
            for (YPondDeviceData device : devices) {
                dailyStatsBackup.add(device.getDailyStats());
                device.setDailyStats(null);
            }
        }
        try {
            return buildUserPromptJson(input);
        } finally {
            if (devices != null) {
                for (int i = 0; i < devices.size(); i++) {
                    devices.get(i).setDailyStats(dailyStatsBackup.get(i));
                }
            }
        }
    }

    /**
     * 构建 user 提示词 JSON 内容（dailyStats 已被剔除）
     * 模板优先读取「参数配置」中的 {@link #CONFIG_KEY_ANALYSIS_USER_PROMPT}（静态部分），
     * 未配置时使用内置默认模板 {@link #defaultUserPrompt()}，
     * 随后用真实用户数据（塘口名称/ID/数据 JSON）替换占位符完成拼接。
     */
    private String buildUserPromptJson(YPondAnalysisInput input) {
        String template = configApi.getConfigValueByKey(CONFIG_KEY_ANALYSIS_USER_PROMPT);
        if (StrUtil.isBlank(template)) {
            template = defaultUserPrompt();
        }
        String prompt = template
                .replace("{pondName}", String.valueOf(input.getPondName()))
                .replace("{pondId}", String.valueOf(input.getPondId()))
                .replace("{pondData}", JsonUtils.toJsonString(input));
        // 动态追加「本塘口实际可用传感器参数」段：无论模板是否被后台参数配置覆盖，都保证 AI 只输出真实安装的传感器指标
        return prompt + buildAvailableSensorSection(input);
    }

    /**
     * 构建「本塘口实际可用传感器参数」说明段（追加在 user 提示词末尾）。
     *
     * 依据设备当前实际上报的属性（lastProperties）动态生成：
     * 1. 明确列出本塘口真实安装的传感器参数，并要求 feed.data / wq.data 只能从中选取；
     * 2. 明确未安装的常见参数（如氨氮/亚硝酸盐等），严禁 AI 输出或编造其数值。
     */
    private String buildAvailableSensorSection(YPondAnalysisInput input) {
        // key -> 展示名（去重且保持稳定顺序）
        Map<String, String> available = new LinkedHashMap<>();
        if (input != null && input.getDevices() != null) {
            for (YPondDeviceData device : input.getDevices()) {
                if (device.getLastProperties() == null) {
                    continue;
                }
                for (String key : device.getLastProperties().keySet()) {
                    available.putIfAbsent(key, propertyDisplayName(key));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n============ 本塘口实际可用传感器参数（务必据此分析）============\n");
        if (available.isEmpty()) {
            sb.append("未采集到任何传感器参数，feed.data / wq.data 请输出空数组，不要编造任何指标数值。\n");
            return sb.toString();
        }
        sb.append("可用参数：").append(String.join("、", available.values())).append("。\n");
        if (!available.containsKey(PROP_NH)) {
            sb.append("本塘口未安装氨氮/亚硝酸盐传感器，严禁输出「氨氮」「亚硝酸盐」及其数值。\n");
        }
        if (!available.containsKey(PROP_PH)) {
            sb.append("本塘口未安装 pH 传感器，严禁输出「pH」及其数值。\n");
        }
        if (!available.containsKey(PROP_TEMP)) {
            sb.append("本塘口未安装水温传感器，严禁输出「水温」及其数值。\n");
        }
        if (!available.containsKey(PROP_DO)) {
            sb.append("本塘口未安装溶氧传感器，严禁输出「溶氧」及其数值。\n");
        }
        sb.append("feed.data / wq.data / aiConclusions 等所有输出中，只允许出现上述可用参数；"
                + "未列出的参数一律视为本塘口没有对应传感器，严禁出现或编造。\n");
        return sb.toString();
    }

    /**
     * 属性 key 转展示名（用于提示词中列举本塘口可用传感器参数）
     */
    private static String propertyDisplayName(String key) {
        if (PROP_DO.equals(key)) {
            return PROP_DO_NAME + "(" + key + ")";
        }
        if (PROP_PH.equals(key)) {
            return PROP_PH_NAME + "(" + key + ")";
        }
        if (PROP_TEMP.equals(key)) {
            return PROP_TEMP_NAME + "(" + key + ")";
        }
        if (PROP_NH.equals(key)) {
            return PROP_NH_NAME + "(" + key + ")";
        }
        if (SWITCH_AERATOR1.equals(key)) {
            return SWITCH_AERATOR1;
        }
        return key;
    }

    /**
     * 内置默认 user 提示词模板（与参数配置项 ai.pond.analysis.user-prompt 的内容保持一致）
     * 占位符说明：{pondName} 塘口名称、{pondId} 塘口ID、{pondData} 塘口数据 JSON
     */
    private static String defaultUserPrompt() {
        return """
                请基于以下塘口数据，对【{pondName}】(塘口ID：{pondId}) 进行今日养殖分析与建议。

                ============ 塘口数据（JSON） ============
                {pondData}
                ============ 数据说明 ============
                - fixedParams 为该塘口安全红线/基准参数（null 表示尚未沉淀，可按行业常规判断）。
                - rules 为 AI 历史沉淀的规律（credibility 为可信度），分析时优先参考。
                - habits 为农户养殖习惯（impact 为影响，已按可信度取前 N 条），建议时需尊重习惯但提示风险。
                - yesterdayResult 为昨日建议摘要（若为 null 说明是首次分析，此时 review 与 homePage.review.items 给 null/空数组，不要输出复盘内容）。
                  adviceContent 为昨日建议全文，用于判断农户是否执行了昨日建议。
                  review 请针对昨日建议复盘，判断依据：
                  - executed（农户是否执行）：结合 reportSummary（农户上报的投喂量/换水/浮头等）与设备开关状态判断是否执行了昨日建议；
                  - effective（效果如何）：结合今日设备数据（溶氧等变化趋势）与昨日建议的问题点回答，
                    例如昨日溶氧高建议开增氧机、今日农户开了1小时但溶氧仍未回升，则 effective 给 poor 并总结"需延长至2小时"；
                  - conclusion 为复盘结论。
                - devices 为塘口设备数据（每个设备为一个网关，网关下挂多个传感器，平台不区分传感器类型），
                  lastProperties 为该设备当前上报的各属性最新值，
                  propertySeries 为各属性近24小时时间序列（连续量，每1小时一个数据点，time 为窗口起始时间，
                  格式 月-日 时:分，value 为该窗口内最近一次上报值），请据此分析属性随时间的变化趋势，
                  尤其关注溶氧在夜间/凌晨的下降曲线，预判缺氧时段并给出氧机开启时间。
                  switchSegments 为开关属性（0/1，如增氧机开关）近24小时的状态时间段
                  （相邻相同状态已合并为一个时间段，startTime 起始、endTime 结束（不含）、value：1-开 0-关；
                  若一整天都是开则只有一个时间段 00:00-次日00:00 开），据此分析氧机每天几点开、几点关、累计开启时长。
                - reportSummary 为近2天（昨日+今日）农民工巡塘上报记录摘要（投喂量、摄食情况、换水、浮头、死亡、水色、病害、用药等），
                  是现场一手观察数据，必须与设备数据综合判断，并体现在 aiConclusions、feed/oxy/wq 的结论与建议中。

                请严格按 system 指定的 JSON 结构输出分析结果。
                """;
    }

}
