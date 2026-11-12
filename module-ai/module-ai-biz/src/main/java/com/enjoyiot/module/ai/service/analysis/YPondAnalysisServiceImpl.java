package com.enjoyiot.module.ai.service.analysis;

import cn.hutool.core.util.StrUtil;
import com.enjoyiot.eiot.temporal.td.config.Constants;
import com.enjoyiot.eiot.temporal.td.dao.TdTemplate;
import com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import com.enjoyiot.module.ai.controller.admin.analysis.vo.YHomePageRespVO;
import com.enjoyiot.module.ai.controller.admin.analysis.vo.YPondAnalysisRespVO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YDailyAnalysisResultDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YFarmerHabitLibraryDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondFixedParamsDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondRuleLibraryDO;
import com.enjoyiot.module.ai.dal.mysql.analysis.YDailyAnalysisResultMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YFarmerHabitLibraryMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YPondFixedParamsMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YPondRuleLibraryMapper;
import com.enjoyiot.module.ai.dto.analysis.YAiOutput;
import com.enjoyiot.module.ai.dto.analysis.YPondAnalysisInput;
import com.enjoyiot.module.ai.dto.analysis.YPondDeviceData;
import com.enjoyiot.module.ai.dto.analysis.YPropertyDailyStat;
import com.enjoyiot.module.ai.dto.analysis.YesterdayAdvice;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDeviceDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YPondDO;
import com.enjoyiot.module.eiot.dal.mysql.farm.YModuleDeviceMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YPondMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.enjoyiot.module.ai.enums.ErrorCodeConstants.*;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.*;

/**
 * 塘口 AI 分析服务实现
 *
 * <p>完整流程（与产品梳理对齐）：</p>
 * <ol>
 *   <li>获取数据：近 24 小时传感器数据（1 小时一个节点）/ 农户上报数据（昨日+今日）/ 塘口规律（可信度前 10 条）/
 *       塘口静态字段 / 塘口基本信息 / 农户习惯，组装为 {@link YPondAnalysisInput}</li>
 *   <li>定义提示词（含输出格式契约），投喂给 AI</li>
 *   <li>AI 按提示词返回结构化分析结果，解析映射到 {@link YAiOutput}，调用 validate() 校验
 *       （校验不通过则当前塘口本次分析视为失败，由 analyzeAllPond 兜底跳过，不落库）</li>
 *   <li>依次写入数据库：
 *       ① 复盘回填昨日主表记录（is_reviewed / review_date / user_executed / execute_effective / execute_discribe / review_conclusion）
 *       ② 分析主表 y_daily_analysis_result（AI 输出全文以 JSON 字符串存 analysis_output，前端查询时解析）
 *       ③ 沉淀画像（y_pond_rule_library / y_farmer_habit_library / y_pond_fixed_params）</li>
 * </ol>
 * <p>复盘逻辑：第一天分析完成插入主表；第二天分析查询出前一天主表数据投喂给 AI，
 * 提示词让 AI 按格式返回应回填的数据，调用回填方法写入昨日主表记录。</p>
 * <p>备注：AI 二次分析（规律库合并、权重更新）与核心规律库提取暂未开发。</p>
 */
@Slf4j
@Service
public class YPondAnalysisServiceImpl implements YPondAnalysisService {

    @Resource
    private YPondDataCollector pondDataCollector;
    @Resource
    private YPondAiPromptBuilder promptBuilder;
    @Resource
    private YDeepSeekClient deepSeekClient;
    @Resource
    private YPondPortraitUpdater portraitUpdater;
    @Resource
    private YDailyAnalysisResultMapper dailyAnalysisResultMapper;
    @Resource
    private YPondFixedParamsMapper pondFixedParamsMapper;
    @Resource
    private YPondRuleLibraryMapper pondRuleLibraryMapper;
    @Resource
    private YFarmerHabitLibraryMapper farmerHabitLibraryMapper;
    @Resource
    private YPondMapper pondMapper;
    @Resource
    private YModuleDeviceMapper moduleDeviceMapper;
    @Resource
    private DeviceApi deviceApi;
    @Resource(name = "tdJdbcTemplate")
    private TdTemplate tdTemplate;

    @Override
    @TenantIgnore // AI 分析模块不涉及多租户，跨租户查询
    @Transactional(rollbackFor = Exception.class)
    public YDailyAnalysisResultDO analyzePond(Long pondId) {
        // 1. 获取数据（近 24 小时传感器序列 / 昨日+今日农户上报 / 规律前10条 / 静态字段 / 基本信息 / 习惯）
        YPondAnalysisInput input = pondDataCollector.collect(pondId);
        if (input == null) {
            throw ServiceExceptionUtil.exception(POND_ANALYSIS_DATA_EMPTY);
        }
        // 昨日主表记录：AI 输入只用昨日建议摘要（adviceContent），但复盘回填需要完整 DO（id/isReviewed）
        YDailyAnalysisResultDO yesterdayResult = dailyAnalysisResultMapper.selectLatestBeforeDate(pondId, LocalDate.now());
        // 2. 定义提示词并调用 AI
        String system = promptBuilder.buildSystemPrompt();
        String user = promptBuilder.buildUserPrompt(input);
        String rawResponse;
        try {
            rawResponse = deepSeekClient.chatJson(system, user);
        } catch (Exception e) {
            log.error("[YPondAnalysisService][analyzePond] DeepSeek 调用失败 pondId: {}", pondId, e);
            throw ServiceExceptionUtil.exception(DEEPSEEK_CALL_FAIL, e.getMessage());
        }
        // 3. 解析结构化输出
        YAiOutput output;
        try {
            String json = extractJson(rawResponse);
            output = JsonUtils.parseObject(json, YAiOutput.class);
        } catch (Exception e) {
            log.error("[YPondAnalysisService][analyzePond] AI 输出解析失败 pondId: {}, raw: {}", pondId, rawResponse, e);
            throw ServiceExceptionUtil.exception(AI_OUTPUT_PARSE_FAIL);
        }
        // 3.5 验证 AI 返回结果是否正确（校验不通过则当前塘口本次分析失败，由 analyzeAllPond 兜底跳过，不落库）
//        try {
//            output.validate();
//        } catch (IllegalArgumentException e) {
//            log.error("[YPondAnalysisService][analyzePond] AI 输出校验失败 pondId: {}, 原因: {}", pondId, e.getMessage());
//            throw ServiceExceptionUtil.exception(AI_OUTPUT_VALIDATE_FAIL, e.getMessage());
//        }

        // 4. 取出 AI 返回的数据，依次执行回写方法：
        // ① 复盘回填：第二天分析时，AI 返回昨日建议的复盘数据（review），回填昨日主表记录
        //   （系统自填：is_reviewed=true、review_date=今天；AI 给出：executed/effective/conclusion/reviewAnalysis）
        backfillReview(yesterdayResult, output);
        // ② 更新规律内容表（分析主表）：AI 当日输出全文以 JSON 字符串存入 analysis_output，用于返回给前端
        //   （系统自填：pondId、analysis_date=今天、is_reviewed=false；AI 给出：adviceContent/adviceCredibility/analysisOutput）
        YDailyAnalysisResultDO result = backfillAnalysisResult(pondId, output);
        // ③ 更新塘口规律：直接插入新的塘口规律到塘口中（AI 给出规律内容，系统自填 verify_count=0 等）
        portraitUpdater.backfillPondRules(pondId, output.getPortraitUpdate());
        // ④ 更新塘口静态字段：按 AI 返回的字段 upsert 固定参数
        portraitUpdater.backfillFixedParams(pondId, output.getPortraitUpdate());
        // ⑤ 更新农户习惯：直接插入新的农户习惯到农户习惯库中（AI 给出习惯内容，系统自填 occurrence_count=1 等）
        portraitUpdater.backfillFarmerHabits(pondId, output.getPortraitUpdate());

        log.info("[YPondAnalysisService][analyzePond] 分析完成 pondId: {}, resultId: {}", pondId, result.getId());
        return result;
    }

    @Override
    @TenantIgnore
    public int analyzeAllPond() {
        // 只需塘口 id，避免查询整行（pond_code/alert_thresholds 等大字段）
        List<YPondDO> ponds = pondMapper.selectList(new LambdaQueryWrapperX<YPondDO>()
                .select(YPondDO::getId));
        if (ponds.isEmpty()) {
            log.info("[YPondAnalysisService][analyzeAllPond] 无塘口可分析");
            return 0;
        }
        int success = 0;
        for (YPondDO pond : ponds) {
            try {
                analyzePond(pond.getId());
                success++;
            } catch (Exception e) {
                log.error("[YPondAnalysisService][analyzeAllPond] 塘口分析失败 pondId: {}", pond.getId(), e);
            }
        }
        log.info("[YPondAnalysisService][analyzeAllPond] 完成，共 {} 个塘口，成功 {} 个", ponds.size(), success);
        return success;
    }

    @Override
    @TenantIgnore
    public YPondAnalysisRespVO getLatestByPondId(Long pondId) {
        YDailyAnalysisResultDO result = dailyAnalysisResultMapper.selectLatestByPondId(pondId);
        if (result == null) {
            return null;
        }
        return assembleRespVO(result);
    }

    @Override
    @TenantIgnore
    public List<YDailyAnalysisResultDO> getRecentList(Long pondId, Integer count) {
        return dailyAnalysisResultMapper.selectList(
                new LambdaQueryWrapperX<YDailyAnalysisResultDO>()
                        .eq(YDailyAnalysisResultDO::getPondId, pondId)
                        .orderByDesc(YDailyAnalysisResultDO::getAnalysisDate)
                        .last("LIMIT " + (count == null || count <= 0 ? 10 : count)));
    }

    @Override
    @TenantIgnore
    public YHomePageRespVO getHomePage(Long pondId) {
        //==根据id 获取塘口信息
        YPondDO pondDetail = pondMapper.selectById(pondId);
        if (pondDetail == null || pondDetail.getCreateTime() == null) {
            return null;
        }
        LocalDateTime pondCreateTime = pondDetail.getCreateTime();
        //==塘口创建时间未满 10 天，首页暂无数据，返回 null；创建满 10 天后才正常返回
        if (LocalDateTime.now().isBefore(pondCreateTime.plusDays(10))) {
            return null;
        }
        //==AI 分析被关闭时，首页直接返回 null（但每日分析仍会执行）
        if (Boolean.FALSE.equals(pondDetail.getAiAnalysisEnabled())) {
            return null;
        }

        // 1. 查询最新分析结果
        YDailyAnalysisResultDO result = dailyAnalysisResultMapper.selectLatestByPondId(pondId);
        if (result == null) {
            return null;
        }
        // 2. 解析 AI 输出（主表 analysis_output JSON 字符串）
        YAiOutput output = parseAiOutput(result);
        // 3. 组装轻量数据：实时设备日聚合 + 固定参数 + 昨日记录（首页指标值实时查询，不再依赖历史快照）
        YPondAnalysisInput input = buildHomeInput(pondId, result);
        // 4. 组装首页七模块
        YHomePageRespVO vo = new YHomePageRespVO();
        vo.setSafety(buildSafety(output));
        vo.setAiConclusions(buildAiConclusions(output, input));
        vo.setFeed(buildFeed(output, input));
        vo.setOxy(buildOxy(pondId, output, input));
        vo.setWq(buildWq(output, input));
        vo.setReview(buildReview(result, output, input));
        vo.setProfile(buildProfile(pondId));
        // 5. 本次分析时间：取首页所用这条最新分析记录（主表）的创建时间，即 AI 分析落库时刻
        vo.setAnalysisTime(result.getCreateTime());
        return vo;
    }

    /**
     * 组装首页轻量数据（首页指标值实时查询）：
     * 设备日聚合（TDengine INTERVAL(1d)）+ 塘口固定参数 + 昨日主表记录（计算投喂增减量用）
     */
    private YPondAnalysisInput buildHomeInput(Long pondId, YDailyAnalysisResultDO current) {
        YPondAnalysisInput input = new YPondAnalysisInput();
        input.setPondId(pondId);
        input.setFixedParams(pondFixedParamsMapper.selectByPondId(pondId));
        // 首页只用最新一天的日统计，3 天仅为无数据回退容错；AI 分析（analyzePond）才需要 24 小时序列
        input.setDevices(pondDataCollector.collectDailyStats(pondId, HOME_STAT_LOOKBACK_DAYS));
        // 昨日是否有分析记录（复盘卡据此判断是否首次分析）
        YDailyAnalysisResultDO yesterday = dailyAnalysisResultMapper.selectLatestBeforeDate(pondId, current.getAnalysisDate());
        if (yesterday != null) {
            YesterdayAdvice advice = new YesterdayAdvice();
            advice.setAdviceContent(yesterday.getAdviceContent());
            advice.setAdviceCredibility(yesterday.getAdviceCredibility());
            input.setYesterdayResult(advice);
        }
        return input;
    }

    /**
     * 解析主表 analysis_output JSON → YAiOutput 对象
     */
    private YAiOutput parseAiOutput(YDailyAnalysisResultDO result) {
        if (result == null || StrUtil.isBlank(result.getAnalysisOutput())) {
            return null;
        }
        return JsonUtils.parseObject(result.getAnalysisOutput(), YAiOutput.class);
    }

    // ========== 首页各模块组装 ==========

    /**
     * 安全指数：优先 AI 输出（homePage.safety.score/level），缺失按风险等级推导（low→88 / mid→68 / high→42）
     */
    private YHomePageRespVO.SafetyVO buildSafety(YAiOutput output) {
        YHomePageRespVO.SafetyVO vo = new YHomePageRespVO.SafetyVO();
        // 优先 AI 输出
        YAiOutput.Safety aiSafety = output != null && output.getHomePage() != null
                ? output.getHomePage().getSafety() : null;
        if (aiSafety != null && aiSafety.getScore() != null) {
            vo.setScore(aiSafety.getScore());
            vo.setLevel(StrUtil.blankToDefault(aiSafety.getLevel(), "mid"));
            return vo;
        }
        // 兜底：建议可信度越高分越高
        Integer score = SAFETY_SCORE_MID;
        String level = RISK_MID;
        if (output != null && output.getAdviceCredibility() != null) {
            Integer credibility = output.getAdviceCredibility();
            if (credibility >= CREDIBILITY_HIGH) {
                score = SAFETY_SCORE_GOOD;
                level = RISK_LOW;
            } else if (credibility >= CREDIBILITY_LOW) {
                score = SAFETY_SCORE_MID;
                level = RISK_MID;
            } else {
                score = SAFETY_SCORE_BAD;
                level = RISK_HIGH;
            }
        }
        vo.setScore(score);
        vo.setLevel(level);
        return vo;
    }

    /**
     * 今日结论：优先 AI 输出（固定 3 条：饲喂/溶氧/水质），不足 3 条或缺失则按现有逻辑兜底拼接补齐
     */
    private List<YHomePageRespVO.AiConclusionVO> buildAiConclusions(YAiOutput output, YPondAnalysisInput input) {
        // 优先 AI 输出（满 3 条才采用，保证契约固定 3 条）
        if (output != null && output.getHomePage() != null
                && output.getHomePage().getAiConclusions() != null
                && output.getHomePage().getAiConclusions().size() >= 3) {
            List<YHomePageRespVO.AiConclusionVO> conclusions = new ArrayList<>();
            for (YAiOutput.AiConclusion ai : output.getHomePage().getAiConclusions()) {
                if (ai == null) {
                    continue;
                }
                YHomePageRespVO.AiConclusionVO vo = new YHomePageRespVO.AiConclusionVO();
                vo.setCls(StrUtil.blankToDefault(ai.getCls(), CLS_OK));
                vo.setSegments(toSegmentVOs(ai.getSegments()));
                conclusions.add(vo);
            }
            if (conclusions.size() >= 3) {
                return conclusions;
            }
        }
        // ===== 兜底拼接（AI 未输出 aiConclusions 时） =====
        List<YHomePageRespVO.AiConclusionVO> conclusions = new ArrayList<>();
        conclusions.add(buildConclusion(CLS_OK, "今日建议按常规投喂。"));
        conclusions.add(buildConclusion(CLS_WARN, "溶氧数据暂缺，请留意夜间状态。"));
        conclusions.add(buildConclusion(CLS_WARN, "水质数据暂缺，请留意塘口状态。"));
        return conclusions;
    }

    /**
     * 构造单条结论（兜底用）
     */
    private YHomePageRespVO.AiConclusionVO buildConclusion(String cls, String text) {
        YHomePageRespVO.AiConclusionVO vo = new YHomePageRespVO.AiConclusionVO();
        vo.setCls(cls);
        vo.setSegments(new ArrayList<>(List.of(buildSegment(text, false))));
        return vo;
    }

    /**
     * 饲喂卡：adjust（今日 vs 昨日投喂量增减百分比）+ 指标列（仅本塘口实际安装的传感器）
     */
    private YHomePageRespVO.FeedVO buildFeed(YAiOutput output, YPondAnalysisInput input) {
        YHomePageRespVO.FeedVO feed = new YHomePageRespVO.FeedVO();

        // 本塘口实际安装的传感器属性（以设备真实上报的 lastProperties 为准）
        Set<String> availableProps = availablePropKeys(input);

        // adjust：优先 AI 输出，缺失给 0
        Integer aiAdjust = output != null && output.getHomePage() != null && output.getHomePage().getFeed() != null
                ? output.getHomePage().getFeed().getAdjust() : null;
        feed.setAdjust(aiAdjust == null ? 0 : aiAdjust);

        // 指标数据：由 AI 输出（label/val/unit/note/infer/cls）。
        // 这些是 AI 在分析过程中总结的"昨日指标"，非实时数据；指标自选、数量不限，
        // 但必须落在本塘口实际可用传感器范围内（未安装氨氮传感器时过滤掉氨氮），禁止凭空出现未安装的参数。
        List<YHomePageRespVO.FeedDataVO> data = new ArrayList<>();
        List<YAiOutput.FeedData> aiData = output != null && output.getHomePage() != null && output.getHomePage().getFeed() != null
                ? output.getHomePage().getFeed().getData() : null;
        if (aiData != null && !aiData.isEmpty()) {
            // 透传 AI 输出：val 纯数值、unit 单独返回，前端分别渲染（避免 val 带单位再拼 unit 造成重复）
            for (YAiOutput.FeedData item : aiData) {
                if (item == null) {
                    continue;
                }
                String label = StrUtil.blankToDefault(item.getLabel(), "指标");
                // 过滤未安装的传感器参数（如无氨氮传感器时 AI 仍输出氨氮）
                if (!isPropAvailable(label, availableProps)) {
                    continue;
                }
                YHomePageRespVO.FeedDataVO vo = new YHomePageRespVO.FeedDataVO();
                vo.setLabel(label);
                vo.setVal(StrUtil.blankToDefault(item.getVal(), "-"));
                vo.setUnit(StrUtil.blankToDefault(item.getUnit(), ""));
                vo.setNote(StrUtil.blankToDefault(item.getNote(), defaultIndicatorNote(label)));
                vo.setInfer(StrUtil.blankToDefault(item.getInfer(), ""));
                vo.setCls(StrUtil.blankToDefault(item.getCls(), CLS_WARN));
                data.add(vo);
            }
        }
        if (data.isEmpty()) {
            // 兜底（AI 未输出指标或全部被过滤时）：按实际传感器查实时数据，val 存纯数字、unit 单独返回
            if (availableProps.isEmpty() || availableProps.contains(PROP_TEMP)) {
                YPropertyDailyStat temp = findLatestStat(input, PROP_TEMP, PROP_TEMP_NAME);
                data.add(buildFeedData(PROP_TEMP_NAME, formatNum(temp != null ? temp.getLatest() : null),
                        temp != null ? UNIT_CELSIUS : "",
                        "过高则减少投喂", "溶氧稳定时水温是投喂参考", temp != null ? judgeCls(temp.getLatest(), input, PROP_TEMP) : CLS_WARN));
            }
            if (availableProps.isEmpty() || availableProps.contains(PROP_DO)) {
                YPropertyDailyStat doStat = findLatestStat(input, PROP_DO, PROP_DO_NAME);
                data.add(buildFeedData("溶氧谷值", formatNum(doStat != null ? doStat.getMin() : null),
                        doStat != null ? UNIT_MG_L : "",
                        "谷值过低需先增氧", "溶氧不足时按 70% 投喂", doStat != null ? judgeDoCls(doStat.getMin(), input) : CLS_WARN));
            }
            // 氨氮：仅本塘口实际安装氨氮传感器时才展示
            if (availableProps.contains(PROP_NH)) {
                YPropertyDailyStat nhStat = findLatestStat(input, PROP_NH, PROP_NH_NAME);
                data.add(buildFeedData(PROP_NH_NAME, formatNum(nhStat != null ? nhStat.getLatest() : null),
                        nhStat != null ? UNIT_MG_L : "",
                        "超标需减料", "氨氮超标按 70% 投喂", nhStat != null ? judgeNhCls(nhStat.getLatest(), input) : CLS_WARN));
            }
        }
        feed.setData(data);

        // AI 建议框：优先 AI 输出 summary/detail，缺失用默认文案兜底
        YAiOutput.AiSuggestion aiSuggestion = output != null && output.getHomePage() != null && output.getHomePage().getFeed() != null
                ? output.getHomePage().getFeed().getAi() : null;
        feed.setAi(buildAiSuggestion(aiSuggestion, "暂无可用的投喂建议。"));
        return feed;
    }

    /**
     * 溶氧卡：临界值 + 24h 溶氧曲线（25 点）+ 增氧方案
     */
    private YHomePageRespVO.OxyVO buildOxy(Long pondId, YAiOutput output, YPondAnalysisInput input) {
        YHomePageRespVO.OxyVO oxy = new YHomePageRespVO.OxyVO();
        YAiOutput.OxySection aiOxy = output != null && output.getHomePage() != null ? output.getHomePage().getOxy() : null;

        // 临界值：固定参数（数据库/默认）
        double critical = extractCritical(input);
        oxy.setCritical(critical);

        // 24h 溶氧曲线（从设备历史按小时聚合，25 点，数据库实时供值）
        List<Double> curve = buildDoCurve24h(pondId);
        double lowest = curve.stream().filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).min().orElse(critical);
        String breakTime = findBreakTime(curve, critical);

        // 标签：优先 AI 输出，缺失按曲线计算
        if (aiOxy != null && aiOxy.getTag() != null && StrUtil.isNotBlank(aiOxy.getTag().getText())) {
            YHomePageRespVO.TagVO tag = new YHomePageRespVO.TagVO();
            tag.setText(aiOxy.getTag().getText());
            tag.setCls(StrUtil.blankToDefault(aiOxy.getTag().getCls(), CLS_WARN));
            oxy.setTag(tag);
        } else {
            YHomePageRespVO.TagVO tag = new YHomePageRespVO.TagVO();
            if (lowest < critical) {
                tag.setText("严重缺氧");
                tag.setCls(CLS_DANGER);
            } else if (lowest < critical + DO_CRITICAL_MARGIN) {
                tag.setText("临界注意");
                tag.setCls(CLS_WARN);
            } else {
                tag.setText("状态良好");
                tag.setCls(CLS_GOOD);
            }
            oxy.setTag(tag);
        }

        // 参考指标列：临界值/跌破时间/最低溶氧必须与 24h 曲线同源，统一由后端按曲线计算。
        // 不再采用 AI 输出的 cols（AI 无法感知实时曲线，易出现"曲线未跌破却有跌破时间""最低溶氧与曲线不符"的矛盾）。
        List<YHomePageRespVO.OxyColVO> cols = new ArrayList<>();
        boolean broken = lowest < critical;
        cols.add(buildOxyCol("临界值", formatNum(BigDecimal.valueOf(critical)), UNIT_MG_L, CLS_WARN));
        cols.add(buildOxyCol("跌破时间", broken ? StrUtil.blankToDefault(breakTime, "无") : "无", "", broken ? CLS_DANGER : CLS_GOOD));
        cols.add(buildOxyCol("最低溶氧", formatNum(BigDecimal.valueOf(lowest)), UNIT_MG_L, broken ? CLS_DANGER : CLS_GOOD));
        oxy.setCols(cols);

        // 曲线：y 轴范围按实际数据动态计算（契约：min 须小于数据最小值、max 须大于数据最大值，
        // 并保证临界值线在 y 轴范围内；否则固定 2~8 会把峰值 >8 的曲线截断/溢出）
        YHomePageRespVO.ChartVO chart = new YHomePageRespVO.ChartVO();
        chart.setData(curve);
        chart.setLabels(List.of("00:00", "06:00", "12:00", "18:00", "24:00"));
        double[] range = calcChartRange(curve, critical);
        chart.setMin(range[0]);
        chart.setMax(range[1]);
        chart.setCritical(critical);
        oxy.setChart(chart);

        // 增氧方案：优先 AI 输出，缺失给"无需增氧"默认
        if (aiOxy != null && aiOxy.getAeroPlan() != null) {
            YHomePageRespVO.AeroPlanVO plan = new YHomePageRespVO.AeroPlanVO();
            plan.setStart(StrUtil.blankToDefault(aiOxy.getAeroPlan().getStart(), "—"));
            plan.setEnd(StrUtil.blankToDefault(aiOxy.getAeroPlan().getEnd(), "—"));
            plan.setHours(StrUtil.blankToDefault(aiOxy.getAeroPlan().getHours(), "无需增氧"));
            oxy.setAeroPlan(plan);
        } else {
            oxy.setAeroPlan(buildDefaultAeroPlan());
        }

        // AI 建议框：优先 AI 输出 summary/detail，缺失用默认文案兜底
        oxy.setAi(buildAiSuggestion(aiOxy != null ? aiOxy.getAi() : null, "暂无可用的溶氧建议。"));
        return oxy;
    }

    /**
     * 水质卡：指标列（仅本塘口实际安装的传感器，如 pH/溶氧/水温/氨氮）
     */
    private YHomePageRespVO.WqVO buildWq(YAiOutput output, YPondAnalysisInput input) {
        YHomePageRespVO.WqVO wq = new YHomePageRespVO.WqVO();
        YAiOutput.WqSection aiWq = output != null && output.getHomePage() != null ? output.getHomePage().getWq() : null;

        // 本塘口实际安装的传感器属性（以设备真实上报的 lastProperties 为准）
        Set<String> availableProps = availablePropKeys(input);

        // 指标数据：由 AI 输出（label/val/unit/note/infer/cls）。
        // 这些是 AI 在分析过程中总结的"昨日水质指标"，非实时数据；指标自选、数量不限，
        // 但必须落在本塘口实际可用传感器范围内（未安装氨氮传感器时过滤掉氨氮），禁止凭空出现未安装的参数。
        List<YHomePageRespVO.WqDataVO> data = new ArrayList<>();
        List<YAiOutput.WqData> aiData = aiWq != null ? aiWq.getData() : null;
        if (aiData != null && !aiData.isEmpty()) {
            // 透传 AI 输出：val 纯数值、unit 单独返回，前端分别渲染（避免 val 带单位再拼 unit 造成重复）
            for (YAiOutput.WqData item : aiData) {
                if (item == null) {
                    continue;
                }
                String label = StrUtil.blankToDefault(item.getLabel(), "指标");
                // 过滤未安装的传感器参数（如无氨氮传感器时 AI 仍输出氨氮）
                if (!isPropAvailable(label, availableProps)) {
                    continue;
                }
                YHomePageRespVO.WqDataVO vo = new YHomePageRespVO.WqDataVO();
                vo.setLabel(label);
                vo.setVal(StrUtil.blankToDefault(item.getVal(), "-"));
                vo.setUnit(StrUtil.blankToDefault(item.getUnit(), ""));
                vo.setNote(StrUtil.blankToDefault(item.getNote(), defaultIndicatorNote(label)));
                vo.setInfer(StrUtil.blankToDefault(item.getInfer(), ""));
                vo.setCls(StrUtil.blankToDefault(item.getCls(), CLS_WARN));
                data.add(vo);
            }
        }
        if (data.isEmpty()) {
            // 兜底（AI 未输出指标或全部被过滤时）：按实际传感器查实时数据，val 存纯数字、unit 单独返回
            if (availableProps.isEmpty() || availableProps.contains(PROP_PH)) {
                YPropertyDailyStat ph = findLatestStat(input, PROP_PH, PROP_PH_NAME);
                data.add(buildWqData(PROP_PH_NAME, formatNum(ph != null ? ph.getLatest() : null), "",
                        "7.5-8.5 适宜", "偏碱偏酸需调水", ph != null ? judgeCls(ph.getLatest(), input, PROP_PH) : CLS_BAD));
            }
            if (availableProps.isEmpty() || availableProps.contains(PROP_DO)) {
                YPropertyDailyStat doStat = findLatestStat(input, PROP_DO, PROP_DO_NAME);
                data.add(buildWqData(PROP_DO_NAME, formatNum(doStat != null ? doStat.getLatest() : null),
                        doStat != null ? UNIT_MG_L : "",
                        NOTE_DO_SUITABLE, "偏低需增氧", doStat != null ? judgeDoCls(doStat.getLatest(), input) : CLS_BAD));
            }
            if (availableProps.isEmpty() || availableProps.contains(PROP_TEMP)) {
                YPropertyDailyStat temp = findLatestStat(input, PROP_TEMP, PROP_TEMP_NAME);
                data.add(buildWqData(PROP_TEMP_NAME, formatNum(temp != null ? temp.getLatest() : null),
                        temp != null ? UNIT_CELSIUS : "",
                        "24-32℃ 适宜", "过高需遮阳降温", temp != null ? judgeCls(temp.getLatest(), input, PROP_TEMP) : CLS_BAD));
            }
            // 氨氮：仅本塘口实际安装氨氮传感器时才展示
            if (availableProps.contains(PROP_NH)) {
                YPropertyDailyStat nh = findLatestStat(input, PROP_NH, PROP_NH_NAME);
                data.add(buildWqData(PROP_NH_NAME, formatNum(nh != null ? nh.getLatest() : null),
                        nh != null ? UNIT_MG_L : "",
                        NOTE_NH_SUITABLE, "超标需换水减料", nh != null ? judgeNhCls(nh.getLatest(), input) : CLS_BAD));
            }
        }
        wq.setData(data);

        // 标签：优先 AI 输出，缺失按 bad 数量
        if (aiWq != null && aiWq.getTag() != null && StrUtil.isNotBlank(aiWq.getTag().getText())) {
            YHomePageRespVO.TagVO tag = new YHomePageRespVO.TagVO();
            tag.setText(aiWq.getTag().getText());
            tag.setCls(StrUtil.blankToDefault(aiWq.getTag().getCls(), CLS_WARN));
            wq.setTag(tag);
        } else {
            YHomePageRespVO.TagVO tag = new YHomePageRespVO.TagVO();
            long badCount = data.stream().filter(d -> CLS_BAD.equals(d.getCls())).count();
            if (badCount >= 2) {
                tag.setText("多项超标");
                tag.setCls(CLS_DANGER);
            } else if (badCount == 1) {
                tag.setText("单项偏高");
                tag.setCls(CLS_WARN);
            } else {
                tag.setText("水质良好");
                tag.setCls(CLS_GOOD);
            }
            wq.setTag(tag);
        }

        // AI 建议框：优先 AI 输出 summary/detail，缺失用默认文案兜底
        wq.setAi(buildAiSuggestion(aiWq != null ? aiWq.getAi() : null, "暂无可用的水质建议。"));
        return wq;
    }

    /**
     * 复盘卡：done + items + conclusion
     */
    private YHomePageRespVO.ReviewVO buildReview(YDailyAnalysisResultDO result, YAiOutput output, YPondAnalysisInput input) {
        YHomePageRespVO.ReviewVO review = new YHomePageRespVO.ReviewVO();
        YAiOutput.ReviewSection aiReviewSection = output != null && output.getHomePage() != null
                ? output.getHomePage().getReview() : null;
        List<YHomePageRespVO.ReviewItemVO> items = new ArrayList<>();

        // 首次分析（昨日无分析记录）：无昨日建议可复盘，显示中性占位，避免"未执行/待观察"误导
        boolean firstAnalysis = input == null || input.getYesterdayResult() == null;
        if (firstAnalysis) {
            String[] names = {"增氧机", "晚间减料", "午后微流水"};
            for (String name : names) {
                YHomePageRespVO.ReviewItemVO item = new YHomePageRespVO.ReviewItemVO();
                item.setName(name);
                item.setState("待复盘");
                item.setStatus(CLS_HALF);
                item.setImpact("暂无昨日建议");
                item.setTag("首次分析");
                item.setTagType(CLS_HALF);
                items.add(item);
            }
            review.setItems(items);
            review.setDone(0);
            calcReviewRate(review);
            review.setAi(buildAiSuggestion(null, "首次分析，暂无昨日建议可复盘，今日起开始沉淀画像。"));
            return review;
        }

        // 明细：优先 AI 输出（数量 = 昨日建议条数），缺失按昨日分析整体结论映射兜底
        List<YAiOutput.ReviewItem> aiItems = aiReviewSection != null ? aiReviewSection.getItems() : null;
        if (aiItems != null && !aiItems.isEmpty()) {
            for (YAiOutput.ReviewItem item : aiItems) {
                if (item == null) {
                    continue;
                }
                YHomePageRespVO.ReviewItemVO vo = new YHomePageRespVO.ReviewItemVO();
                vo.setName(StrUtil.blankToDefault(item.getName(), "建议动作"));
                // state 由后端按 status 派生，AI 不再输出（消除 state/status 双份表达的不一致）
                vo.setState(CLS_DONE.equals(item.getStatus()) ? "已执行" : CLS_HALF.equals(item.getStatus()) ? "部分执行" : "未执行");
                vo.setStatus(StrUtil.blankToDefault(item.getStatus(), CLS_NO));
                vo.setImpact(StrUtil.blankToDefault(item.getImpact(), "-"));
                vo.setTag(StrUtil.blankToDefault(item.getTag(), "待观察"));
                vo.setTagType(StrUtil.blankToDefault(item.getTagType(), CLS_HALF));
                items.add(vo);
            }
        } else {
            YAiOutput.Review aiReview = output != null ? output.getReview() : null;
            // 是否执行映射：yes→done / partial→half / no→no
            String status = CLS_NO;
            if (aiReview != null && EXEC_YES.equals(aiReview.getExecuted())) {
                status = CLS_DONE;
            } else if (aiReview != null && EXEC_PARTIAL.equals(aiReview.getExecuted())) {
                status = CLS_HALF;
            }
            // 执行效果映射：excellent→有效/good / good→达标/good / fair→未达标/half / poor→恶化/bad
            String tagType = CLS_HALF;
            String tagText = "待观察";
            if (aiReview != null) {
                switch (StrUtil.blankToDefault(aiReview.getEffective(), "")) {
                    case EFFECT_EXCELLENT:
                    case EFFECT_GOOD:
                        tagType = CLS_GOOD;
                        tagText = "有效";
                        break;
                    case EFFECT_FAIR:
                        tagType = CLS_HALF;
                        tagText = "未达标";
                        break;
                    case EFFECT_POOR:
                        tagType = CLS_BAD;
                        tagText = "恶化";
                        break;
                    default:
                        break;
                }
            }
            // 昨日建议三项：增氧机 / 晚间减料 / 午后微流水
            items.add(buildReviewItem("增氧机", status, tagType, tagText, "溶氧趋于稳定"));
            items.add(buildReviewItem("晚间减料", status, tagType, tagText, "投喂负担减轻"));
            items.add(buildReviewItem("午后微流水", status, tagType, tagText, "水质趋于洁净"));
        }
        review.setItems(items);
        review.setDone((int) items.stream().filter(i -> CLS_DONE.equals(i.getStatus())).count());
        calcReviewRate(review);
        // AI 建议框：优先 AI 输出 summary/detail，缺失用复盘结论兜底
        String fallbackConclusion = output != null && output.getReview() != null && StrUtil.isNotBlank(output.getReview().getConclusion())
                ? output.getReview().getConclusion()
                : StrUtil.blankToDefault(result.getReviewConclusion(), "昨日建议执行到位，今日按计划推进。");
        review.setAi(buildAiSuggestion(aiReviewSection != null ? aiReviewSection.getAi() : null, fallbackConclusion));
        return review;
    }

    /**
     * 复盘执行率与等级：rate = done/items.length × 100；level：≥80% low / 60-79% mid / <60% high
     */
    private void calcReviewRate(YHomePageRespVO.ReviewVO review) {
        List<YHomePageRespVO.ReviewItemVO> items = review.getItems();
        int total = items == null ? 0 : items.size();
        int done = review.getDone() == null ? 0 : review.getDone();
        int rate = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        review.setRate(rate);
        review.setLevel(rate >= 80 ? "low" : rate >= 60 ? "mid" : "high");
    }

    /**
     * 画像卡：4 项统计
     */
    private YHomePageRespVO.ProfileVO buildProfile(Long pondId) {
        YHomePageRespVO.ProfileVO profile = new YHomePageRespVO.ProfileVO();
        // 分析天数
        Long totalDays = dailyAnalysisResultMapper.selectCount(new LambdaQueryWrapperX<YDailyAnalysisResultDO>()
                .eq(YDailyAnalysisResultDO::getPondId, pondId));
        // 已复盘且执行有效（优/良）
        Long reviewed = dailyAnalysisResultMapper.selectCount(new LambdaQueryWrapperX<YDailyAnalysisResultDO>()
                .eq(YDailyAnalysisResultDO::getPondId, pondId)
                .eq(YDailyAnalysisResultDO::getIsReviewed, true)
                .in(YDailyAnalysisResultDO::getExecuteEffective, List.of("优", "良")));
        // 沉淀规律
        Long rules = pondRuleLibraryMapper.selectCount(new LambdaQueryWrapperX<YPondRuleLibraryDO>()
                .eq(YPondRuleLibraryDO::getPondId, pondId));
        Long habits = farmerHabitLibraryMapper.selectCount(new LambdaQueryWrapperX<YFarmerHabitLibraryDO>()
                .eq(YFarmerHabitLibraryDO::getPondId, pondId));

        List<YHomePageRespVO.StatVO> stats = new ArrayList<>();
        stats.add(buildStat(String.valueOf(totalDays), "分析天数"));
        stats.add(buildStat(String.valueOf(rules + habits), "累计建议"));
        stats.add(buildStat(calcAdoptRate(totalDays, reviewed), "采纳率"));
        stats.add(buildStat(String.valueOf(rules), "沉淀规律"));
        profile.setStats(stats);
        return profile;
    }

    // ========== 24h 溶氧曲线 ==========

    /**
     * 构建 24h 溶氧曲线（25 点：0 时~24 时）。
     * 取"昨天 0 点 ~ 今天 0 点"的完整 24 小时（与 AI 分析口径一致，展示昨日数据），
     * 从塘口全部设备的 DO 属性历史按小时聚合（多设备取平均）。
     */
    private List<Double> buildDoCurve24h(Long pondId) {
        List<Double> curve = new ArrayList<>(Collections.nCopies(CURVE_POINTS, null));
        // 固定东八区：窗口口径与 TDengine 连接 timezone=UTC-8、业务数据时区保持一致，
        // 避免依赖 JVM/容器时区（systemDefault）导致"昨天 0 点"整体偏移
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long end = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli(); // 今天 0 点（东八区）
        long start = end - CURVE_HOURS * 3600 * 1000L; // 昨天 0 点（东八区）
        // 桶：每小时一个，i 对应 (start + i*1h, start + (i+1)*1h)
        double[] sums = new double[CURVE_HOURS];
        int[] counts = new int[CURVE_HOURS];
        List<YModuleDeviceDO> devices = moduleDeviceMapper.selectList(new LambdaQueryWrapperX<YModuleDeviceDO>()
                .eq(YModuleDeviceDO::getPondId, pondId));
        for (YModuleDeviceDO device : devices) {
            try {
                // 产品分析白名单：仅查询配置了分析参数且包含 DO 属性的设备（与首页日聚合/AI 分析口径一致），
                // 未配置的产品（如太阳能、摄像头）不参与曲线计算。
                DeviceInfo deviceInfo = deviceApi.getDeviceInfoFromCache(device.getDeviceId());
                if (deviceInfo == null) {
                    continue;
                }
                List<String> analyzeProps = POND_ANALYSIS_PRODUCT_PROPERTIES.get(deviceInfo.getProductKey());
                if (analyzeProps == null || !analyzeProps.contains(PROP_DO)) {
                    continue;
                }
                // TDengine SQL 侧按小时聚合（INTERVAL(1h) + last，与手动查询口径一致）：
                // select _wstart, last(do) from device_property_xxx where ... interval(1h)
                // 替代拉取全量原始点在 JVM 内分桶平均：既避免原始点过多触发 limit 截断，
                // 也避免超级表原始点查询的隐性差异
                String tableName = Constants.getProductPropertySTableName(deviceInfo.getProductKey());
                String fieldName = (PROP_DO.contains(".") ? "`" + PROP_DO + "`" : PROP_DO).toLowerCase();
                String sql = String.format(
                        "select _wstart as time, last(%s) as last_value from %s "
                                + "where device_id=? and time>=? and time<=? partition by device_id interval(1h)",
                        fieldName, tableName);
                List<HourlyLastRow> rows = tdTemplate.query(sql,
                        new BeanPropertyRowMapper<>(HourlyLastRow.class), device.getDeviceId(), start, end);
                for (HourlyLastRow row : rows) {
                    if (row.getTime() == null || row.getLastValue() == null) {
                        continue;
                    }
                    int idx = (int) ((row.getTime() - start) / (3600 * 1000L));
                    if (idx < 0 || idx >= CURVE_HOURS) {
                        continue;
                    }
                    sums[idx] += row.getLastValue();
                    counts[idx]++;
                }
            } catch (Exception e) {
                log.warn("[YPondAnalysisService][buildDoCurve24h] 查询设备历史失败 deviceId: {}", device.getDeviceId(), e);
            }
        }
        // 求每小时均值；中间空洞用上一个非空值前向填充（短时缺测不断线），
        // 起始空段（前导无数据，如设备当日未上报）保持 null 不伪造数据，
        // 让曲线如实反映上报时段，避免出现手动查询里不存在的一段平线
        double lastValue = 0;
        for (int i = 0; i < CURVE_HOURS; i++) {
            if (counts[i] > 0) {
                double avg = round1(sums[i] / counts[i]);
                curve.set(i, avg);
                lastValue = avg;
            } else {
                // 起始空段 lastValue 为 0 → 保持 null；中间空洞 → 用上一个非空值填充
                curve.set(i, lastValue > 0 ? lastValue : null);
            }
        }
        // 第 25 个点（24 时）用最后一个非空值兜底（结束时刻沿用最后一小时均值）
        Double lastNonNull = null;
        for (int i = CURVE_HOURS - 1; i >= 0; i--) {
            if (curve.get(i) != null) {
                lastNonNull = curve.get(i);
                break;
            }
        }
        curve.set(CURVE_POINTS - 1, lastNonNull);
        // 全部为空 → 默认 5.0 兜底
        if (curve.stream().allMatch(java.util.Objects::isNull)) {
            return Collections.nCopies(CURVE_POINTS, CURVE_DEFAULT_VALUE);
        }
        return curve;
    }

    /**
     * TDengine INTERVAL(1h) 查询结果行：窗口起始时间（毫秒）+ 该小时 do 最后值
     */
    @Data
    static class HourlyLastRow {

        /** 窗口起始时间（毫秒时间戳） */
        private Long time;

        /** 该小时 last(do) 聚合值 */
        private Double lastValue;

    }

    /**
     * 计算 24h 溶氧曲线 y 轴动态范围。
     * 契约（首页接口文档）：min 须小于数据最小值、max 须大于数据最大值；
     * 同时把临界值纳入下界，保证临界线不被画出 y 轴。
     * 全部无数据时回退默认 [CHART_MIN, CHART_MAX]。
     */
    private double[] calcChartRange(List<Double> curve, double critical) {
        if (curve == null || curve.stream().noneMatch(java.util.Objects::nonNull)) {
            return new double[]{CHART_MIN, CHART_MAX};
        }
        double minVal = curve.stream().filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).min().orElse(CHART_MIN);
        double maxVal = curve.stream().filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(CHART_MAX);
        // 余量：跨度的 15%，至少 0.5，避免曲线贴边；恒值数据也能撑开 min < val < max
        double span = Math.max(maxVal - minVal, 1.0);
        double padding = Math.max(span * 0.15, 0.5);
        double min = Math.floor(Math.min(minVal, critical) - padding);
        double max = Math.ceil(maxVal + padding);
        return new double[]{min, max};
    }

    /**
     * 查找曲线中首次跌破临界的时间（HH:mm），未跌破返回 null
     */
    private String findBreakTime(List<Double> curve, double critical) {
        if (curve == null) {
            return null;
        }
        for (int i = 0; i < curve.size(); i++) {
            Double v = curve.get(i);
            if (v != null && v < critical) {
                int hour = i == CURVE_POINTS - 1 ? 0 : i; // 第 25 个点视为 00:00
                return String.format("%02d:00", hour);
            }
        }
        return null;
    }

    // ========== 指标阈值判断 ==========

    private String judgeCls(BigDecimal value, YPondAnalysisInput input, String key) {
        if (value == null) {
            return CLS_WARN;
        }
        YPondFixedParamsDO fp = input != null ? input.getFixedParams() : null;
        BigDecimal min = null;
        BigDecimal max = null;
        if (fp != null) {
            switch (key) {
                case PROP_TEMP:
                    min = fp.getTempMin();
                    max = fp.getTempMax();
                    break;
                case PROP_PH:
                    min = fp.getPhMin();
                    max = fp.getPhMax();
                    break;
                default:
                    break;
            }
        }
        if (min != null && value.compareTo(min) < 0) {
            return CLS_BAD;
        }
        if (max != null && value.compareTo(max) > 0) {
            return CLS_BAD;
        }
        return CLS_GOOD;
    }

    private String judgeDoCls(BigDecimal value, YPondAnalysisInput input) {
        if (value == null) {
            return CLS_WARN;
        }
        BigDecimal min = input != null && input.getFixedParams() != null
                ? input.getFixedParams().getDissolvedOxygenMin() : null;
        if (min == null) {
            min = BigDecimal.valueOf(DEFAULT_DO_CRITICAL);
        }
        return value.compareTo(min) < 0 ? CLS_BAD : CLS_GOOD;
    }

    private String judgeNhCls(BigDecimal value, YPondAnalysisInput input) {
        if (value == null) {
            return CLS_WARN;
        }
        BigDecimal max = input != null && input.getFixedParams() != null
                ? input.getFixedParams().getAmmoniaNitrogenMax() : null;
        if (max == null) {
            max = BigDecimal.valueOf(DEFAULT_NH_MAX);
        }
        return value.compareTo(max) > 0 ? CLS_BAD : CLS_GOOD;
    }

    /**
     * 从设备日聚合数据中查找指定属性最新一天的统计。
     * <p>多台设备包含同一属性时，取最新一天全部设备的 min/max/avg/latest 平均值
     * （与 24h 溶氧曲线 {@link #buildDoCurve24h} 的"全塘均值"口径一致），
     * 避免只取到设备列表第一台的值导致卡片与曲线口径不一致。</p>
     */
    private YPropertyDailyStat findLatestStat(YPondAnalysisInput input, String key, String name) {
        if (input == null || input.getDevices() == null) {
            return null;
        }
        // 1. 确定最新有数据的日期（多设备任一命中即推进）
        LocalDate latestDate = null;
        for (YPondDeviceData device : input.getDevices()) {
            if (device.getDailyStats() == null) {
                continue;
            }
            for (YPropertyDailyStat stat : device.getDailyStats()) {
                if (!matchProperty(stat, key, name) || stat.getDate() == null) {
                    continue;
                }
                if (latestDate == null || stat.getDate().isAfter(latestDate)) {
                    latestDate = stat.getDate();
                }
            }
        }
        if (latestDate == null) {
            return null;
        }
        // 2. 汇总最新一天全部设备的统计，逐字段取平均
        YPropertyDailyStat sample = null;
        BigDecimal sumMin = BigDecimal.ZERO;
        BigDecimal sumMax = BigDecimal.ZERO;
        BigDecimal sumAvg = BigDecimal.ZERO;
        BigDecimal sumLatest = BigDecimal.ZERO;
        int minN = 0;
        int maxN = 0;
        int avgN = 0;
        int latestN = 0;
        int countTotal = 0;
        for (YPondDeviceData device : input.getDevices()) {
            if (device.getDailyStats() == null) {
                continue;
            }
            for (YPropertyDailyStat stat : device.getDailyStats()) {
                if (!matchProperty(stat, key, name) || stat.getDate() == null
                        || !stat.getDate().isEqual(latestDate)) {
                    continue;
                }
                if (sample == null) {
                    sample = stat;
                }
                if (stat.getMin() != null) {
                    sumMin = sumMin.add(stat.getMin());
                    minN++;
                }
                if (stat.getMax() != null) {
                    sumMax = sumMax.add(stat.getMax());
                    maxN++;
                }
                if (stat.getAvg() != null) {
                    sumAvg = sumAvg.add(stat.getAvg());
                    avgN++;
                }
                if (stat.getLatest() != null) {
                    sumLatest = sumLatest.add(stat.getLatest());
                    latestN++;
                }
                if (stat.getCount() != null) {
                    countTotal += stat.getCount();
                }
            }
        }
        if (sample == null) {
            return null;
        }
        YPropertyDailyStat merged = new YPropertyDailyStat();
        merged.setDeviceId(sample.getDeviceId());
        merged.setDeviceName(sample.getDeviceName());
        merged.setPropertyKey(sample.getPropertyKey());
        merged.setPropertyName(sample.getPropertyName());
        merged.setUnit(sample.getUnit());
        merged.setDate(latestDate);
        merged.setMin(minN > 0 ? avg2(sumMin, minN) : null);
        merged.setMax(maxN > 0 ? avg2(sumMax, maxN) : null);
        merged.setAvg(avgN > 0 ? avg2(sumAvg, avgN) : null);
        merged.setLatest(latestN > 0 ? avg2(sumLatest, latestN) : null);
        merged.setCount(countTotal);
        return merged;
    }

    /**
     * 判断日统计记录是否属于指定属性（key 或中文名任一匹配）
     */
    private boolean matchProperty(YPropertyDailyStat stat, String key, String name) {
        return key.equals(stat.getPropertyKey()) || name.equals(stat.getPropertyName());
    }

    /**
     * 求均值（保留 2 位小数，四舍五入）
     */
    private BigDecimal avg2(BigDecimal sum, int n) {
        return sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    // ========== 小工具 ==========

    private Double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 纯数值格式化（保留 1 位小数、去尾零），null → "-"。单位由 unit 字段单独返回、前端单独渲染，
     * 避免与前端「数值 + 单位」拼接渲染重复。
     */
    private String formatNum(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 指标 label 模糊匹配：label 与任一关键词互相包含即命中。
     * 兼容 AI 自由文本输出的变体（如 温度/溶解氧/DO/NH/PH值 等），避免实时供值因精确匹配失败而显示 "-"。
     */
    private boolean matchIndicator(String label, String... keywords) {
        if (StrUtil.isBlank(label)) {
            return false;
        }
        String lower = label.toLowerCase();
        for (String kw : keywords) {
            if (StrUtil.isBlank(kw)) {
                continue;
            }
            String k = kw.toLowerCase();
            if (lower.contains(k) || k.contains(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本塘口实际可用传感器属性 key：取设备真实上报的 lastProperties（已按产品分析白名单过滤），
     * 反映"该塘口实际装了哪些传感器"，而非静态白名单里配置了哪些参数。
     * 用于过滤 AI 输出中凭空出现的指标（如未安装氨氮传感器时不得返回氨氮）。
     */
    private Set<String> availablePropKeys(YPondAnalysisInput input) {
        Set<String> keys = new LinkedHashSet<>();
        if (input == null || input.getDevices() == null) {
            return keys;
        }
        for (YPondDeviceData device : input.getDevices()) {
            if (device == null || device.getLastProperties() == null) {
                continue;
            }
            keys.addAll(device.getLastProperties().keySet());
        }
        return keys;
    }

    /**
     * 判断 AI 输出的指标 label 是否属于本塘口实际可用传感器。
     * <p>仅对可识别的属性指标（溶氧/pH/水温/氨氮）做强校验：未安装的直接过滤；
     * 无法识别的指标（如"投喂量""摄食情况"等非传感器指标）不做过滤，避免误杀 AI 的合理输出。</p>
     */
    private boolean isPropAvailable(String label, Set<String> availableProps) {
        if (availableProps == null || availableProps.isEmpty()) {
            return true;
        }
        if (matchIndicator(label, PROP_NH_NAME, PROP_NH, "亚硝酸盐")) {
            return availableProps.contains(PROP_NH);
        }
        if (matchIndicator(label, PROP_DO_NAME, PROP_DO, "溶解氧")) {
            return availableProps.contains(PROP_DO);
        }
        if (matchIndicator(label, PROP_PH_NAME, PROP_PH, "酸碱度")) {
            return availableProps.contains(PROP_PH);
        }
        if (matchIndicator(label, PROP_TEMP_NAME, PROP_TEMP, "温度")) {
            return availableProps.contains(PROP_TEMP);
        }
        return true;
    }

    /**
     * AI 未输出 note 时的默认说明文案（按指标匹配）
     */
    private String defaultIndicatorNote(String label) {
        if (matchIndicator(label, PROP_PH_NAME)) {
            return "7.5-8.5 适宜";
        }
        if (matchIndicator(label, PROP_DO_NAME, "溶解氧", "DO")) {
            return "≥4.0mg/L 适宜";
        }
        if (matchIndicator(label, PROP_TEMP_NAME, "温度")) {
            return "24-32℃ 适宜";
        }
        if (matchIndicator(label, PROP_NH_NAME)) {
            return "≤0.5mg/L 适宜";
        }
        return "";
    }

    private YHomePageRespVO.SegmentVO buildSegment(String text, boolean strong) {
        YHomePageRespVO.SegmentVO seg = new YHomePageRespVO.SegmentVO();
        seg.setText(text);
        seg.setStrong(strong);
        return seg;
    }

    /**
     * AI 建议框：优先取 AI 输出的 summary/detail（富文本片段），缺失用一段兜底文案（AI：xxx）
     */
    private YHomePageRespVO.AiSuggestionVO buildAiSuggestion(YAiOutput.AiSuggestion ai, String fallbackText) {
        YHomePageRespVO.AiSuggestionVO vo = new YHomePageRespVO.AiSuggestionVO();
        if (ai != null && ai.getSummary() != null && !ai.getSummary().isEmpty()) {
            vo.setSummary(toSegmentVOs(ai.getSummary()));
        } else if (StrUtil.isNotBlank(fallbackText)) {
            vo.setSummary(new ArrayList<>(List.of(
                    buildSegment("AI：", true),
                    buildSegment(fallbackText, false))));
        } else {
            vo.setSummary(new ArrayList<>(List.of(buildSegment("AI：暂无建议。", false))));
        }
        if (ai != null && ai.getDetail() != null && !ai.getDetail().isEmpty()) {
            vo.setDetail(toSegmentVOs(ai.getDetail()));
        }
        return vo;
    }

    /**
     * AI 富文本片段 → VO 片段
     */
    private List<YHomePageRespVO.SegmentVO> toSegmentVOs(List<YAiOutput.Segment> segments) {
        List<YHomePageRespVO.SegmentVO> list = new ArrayList<>();
        if (segments == null) {
            return list;
        }
        for (YAiOutput.Segment segment : segments) {
            if (segment == null) {
                continue;
            }
            list.add(buildSegment(StrUtil.blankToDefault(segment.getText(), ""), Boolean.TRUE.equals(segment.getStrong())));
        }
        return list;
    }

    private YHomePageRespVO.FeedDataVO buildFeedData(String label, String val, String unit, String note, String infer, String cls) {
        YHomePageRespVO.FeedDataVO data = new YHomePageRespVO.FeedDataVO();
        data.setLabel(label);
        data.setVal(val);
        data.setUnit(unit);
        data.setNote(note);
        data.setInfer(infer);
        data.setCls(cls);
        return data;
    }

    private YHomePageRespVO.OxyColVO buildOxyCol(String label, String val, String unit, String cls) {
        YHomePageRespVO.OxyColVO col = new YHomePageRespVO.OxyColVO();
        col.setLabel(label);
        col.setVal(val);
        col.setUnit(unit);
        col.setCls(cls);
        return col;
    }

    private YHomePageRespVO.WqDataVO buildWqData(String label, String val, String unit, String note, String infer, String cls) {
        YHomePageRespVO.WqDataVO data = new YHomePageRespVO.WqDataVO();
        data.setLabel(label);
        data.setVal(val);
        data.setUnit(unit);
        data.setNote(note);
        data.setInfer(infer);
        data.setCls(cls);
        return data;
    }

    private YHomePageRespVO.ReviewItemVO buildReviewItem(String name, String status, String tagType, String tag, String impact) {
        YHomePageRespVO.ReviewItemVO item = new YHomePageRespVO.ReviewItemVO();
        item.setName(name);
        item.setState(CLS_DONE.equals(status) ? "已执行" : CLS_HALF.equals(status) ? "部分执行" : "未执行");
        item.setStatus(status);
        item.setTag(tag);
        item.setTagType(tagType);
        item.setImpact(impact);
        return item;
    }

    private YHomePageRespVO.StatVO buildStat(String num, String label) {
        YHomePageRespVO.StatVO stat = new YHomePageRespVO.StatVO();
        stat.setNum(num);
        stat.setLabel(label);
        return stat;
    }

    /**
     * 溶氧临界值：优先取固定参数，缺省 4.0
     */
    private double extractCritical(YPondAnalysisInput input) {
        if (input != null && input.getFixedParams() != null
                && input.getFixedParams().getDissolvedOxygenMin() != null) {
            return input.getFixedParams().getDissolvedOxygenMin().doubleValue();
        }
        return DEFAULT_DO_CRITICAL;
    }

    /**
     * 增氧方案默认值：无需增氧
     */
    private YHomePageRespVO.AeroPlanVO buildDefaultAeroPlan() {
        YHomePageRespVO.AeroPlanVO plan = new YHomePageRespVO.AeroPlanVO();
        plan.setStart("—");
        plan.setEnd("—");
        plan.setHours("无需增氧");
        return plan;
    }

    /**
     * 采纳率：已复盘且执行有效（优/良）占总分析天数百分比
     */
    private String calcAdoptRate(Long totalDays, Long reviewed) {
        if (totalDays == null || totalDays == 0) {
            return "0%";
        }
        long rate = Math.round(reviewed * 100.0 / totalDays);
        return rate + "%";
    }

    /**
     * 组装前端返回 VO
     */
    private YPondAnalysisRespVO assembleRespVO(YDailyAnalysisResultDO result) {
        YPondAnalysisRespVO vo = new YPondAnalysisRespVO();
        vo.setId(result.getId());
        vo.setPondId(result.getPondId());
        vo.setAnalysisDate(result.getAnalysisDate());
        vo.setAdviceContent(result.getAdviceContent());
        vo.setAdviceCredibility(result.getAdviceCredibility());
        vo.setAnalysisOutput(result.getAnalysisOutput());
        vo.setIsReviewed(result.getIsReviewed());
        vo.setReviewDate(result.getReviewDate());
        vo.setUserExecuted(result.getUserExecuted());
        vo.setExecuteEffective(result.getExecuteEffective());
        vo.setExecuteDiscribe(result.getExecuteDiscribe());
        vo.setReviewConclusion(result.getReviewConclusion());
        vo.setCreateTime(result.getCreateTime());
        vo.setUpdateTime(result.getUpdateTime());

        // 塘口名称
        YPondDO pond = pondMapper.selectById(result.getPondId());
        if (pond != null) {
            vo.setPondName(pond.getPondName());
        }

        // AI 输出明细（主表 analysis_output JSON 解析）
        YAiOutput output = parseAiOutput(result);
        if (output != null) {
            vo.setReview(output.getReview());
            vo.setPortraitUpdate(output.getPortraitUpdate());
        }

        // 塘口画像
        YPondFixedParamsDO fixedParams = pondFixedParamsMapper.selectByPondId(result.getPondId());
        vo.setFixedParams(fixedParams);
        vo.setRules(pondRuleLibraryMapper.selectListByPondId(result.getPondId()));
        vo.setHabits(farmerHabitLibraryMapper.selectListByPondId(result.getPondId()));
        return vo;
    }

    /**
     * 提取 AI 返回中的 JSON（兼容 ```json 包裹）
     */
    private String extractJson(String content) {
        if (StrUtil.isBlank(content)) {
            throw new IllegalArgumentException("AI 返回内容为空");
        }
        String json = content.trim();
        // 去除 ```json 或 ``` 代码块标记
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start + 1, end).trim();
            }
        }
        // 取第一个 { 到最后一个 }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        return json;
    }

    /**
     * 构建建议全文（用于 y_daily_analysis_result.advice_content）
     *
     * 从 homePage 各模块的 AI 建议摘要派生（顶层建议字段已移除），
     * 保持「昨日建议全文」语义不变：供次日复盘判断农户是否执行昨日建议。
     */
    private String buildAdviceContent(YAiOutput output) {
        if (output == null || output.getHomePage() == null) {
            return "";
        }
        YAiOutput.HomePage homePage = output.getHomePage();
        StringBuilder sb = new StringBuilder();
        appendAiSummary(sb, "【饲喂建议】", homePage.getFeed() != null ? homePage.getFeed().getAi() : null);
        appendAiSummary(sb, "【溶氧建议】", homePage.getOxy() != null ? homePage.getOxy().getAi() : null);
        appendAiSummary(sb, "【水质建议】", homePage.getWq() != null ? homePage.getWq().getAi() : null);
        return sb.toString();
    }

    /**
     * 追加某模块的 AI 建议摘要（summary 为空时回退 detail）
     */
    private void appendAiSummary(StringBuilder sb, String title, YAiOutput.AiSuggestion ai) {
        if (ai == null) {
            return;
        }
        String text = segmentsText(ai.getSummary());
        if (StrUtil.isBlank(text)) {
            text = segmentsText(ai.getDetail());
        }
        if (StrUtil.isNotBlank(text)) {
            sb.append(title).append(text).append("\n");
        }
    }

    /**
     * 富文本片段拼接为纯文本（去除 strong 标记，仅取 text）
     */
    private String segmentsText(List<YAiOutput.Segment> segments) {
        if (segments == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (YAiOutput.Segment segment : segments) {
            if (segment != null && segment.getText() != null) {
                sb.append(segment.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 回写方法：更新规律内容表（分析主表 y_daily_analysis_result）
     *
     * <p>将 AI 当日分析输出全文（结构化 JSON）以字符串形式存入 analysis_output 字段，
     * 用于返回给前端（前端查询时解析该 JSON 展示各模块数据）。</p>
     *
     * <p>字段来源约定：</p>
     * <ul>
     *   <li>系统自填：pondId、analysisDate=今天、isReviewed=false、createTime/updateTime（框架自动填充）；</li>
     *   <li>AI 给出：adviceContent（建议全文）、adviceCredibility（建议可信度）、
     *       analysisOutput（当日分析输出全文 JSON，含 riskSummary/feedingAdvice 等前端展示字段）。</li>
     * </ul>
     *
     * @param pondId 塘口ID
     * @param output AI 当日分析输出（已通过 {@link YAiOutput#validate()} 校验）
     * @return 新插入的主表记录
     */
    private YDailyAnalysisResultDO backfillAnalysisResult(Long pondId, YAiOutput output) {
        YDailyAnalysisResultDO result = new YDailyAnalysisResultDO();
        result.setPondId(pondId);
        result.setAnalysisDate(LocalDate.now());
        result.setAdviceContent(buildAdviceContent(output));
        result.setAdviceCredibility(output.getAdviceCredibility());
        result.setAnalysisOutput(JsonUtils.toJsonString(output));
        result.setIsReviewed(false);
        dailyAnalysisResultMapper.insert(result);
        log.info("[YPondAnalysisService][backfillAnalysisResult] 分析主表已写入 resultId: {}", result.getId());
        return result;
    }

    /**
     * 复盘回填：第二天分析时，AI 对昨日建议返回复盘数据（review），
     * 回填到昨日主表记录的复盘字段（is_reviewed / review_date / user_executed /
     * execute_effective / execute_discribe / review_conclusion）。
     * 已复盘（is_reviewed=true）的记录不重复回填。
     */
    private void backfillReview(YDailyAnalysisResultDO yesterday, YAiOutput output) {
        if (yesterday == null || output == null || output.getReview() == null) {
            return;
        }
        // 已复盘的不重复回填
        if (Boolean.TRUE.equals(yesterday.getIsReviewed())) {
            return;
        }
        YAiOutput.Review review = output.getReview();
        YDailyAnalysisResultDO update = new YDailyAnalysisResultDO();
        update.setId(yesterday.getId());
        update.setIsReviewed(true);
        update.setReviewDate(LocalDate.now());
        update.setUserExecuted(mapExecuted(review.getExecuted()));
        update.setExecuteEffective(mapEffective(review.getEffective()));
        update.setExecuteDiscribe(review.getReviewAnalysis());
        update.setReviewConclusion(review.getConclusion());
        dailyAnalysisResultMapper.updateById(update);
        log.info("[YPondAnalysisService][backfillReview] 昨日分析已复盘回填 resultId: {}", yesterday.getId());
    }

    /**
     * 执行状态映射：yes→是 / partial→部分执行 / no→否
     */
    private String mapExecuted(String executed) {
        if (EXEC_YES.equals(executed)) {
            return "是";
        }
        if (EXEC_PARTIAL.equals(executed)) {
            return "部分执行";
        }
        return "否";
    }

    /**
     * 执行效果映射：excellent→优 / good→良 / fair→一般 / poor→差 / 其他→待观察
     */
    private String mapEffective(String effective) {
        switch (StrUtil.blankToDefault(effective, "")) {
            case EFFECT_EXCELLENT:
                return "优";
            case EFFECT_GOOD:
                return "良";
            case EFFECT_FAIR:
                return "一般";
            case EFFECT_POOR:
                return "差";
            default:
                return "待观察";
        }
    }
}
