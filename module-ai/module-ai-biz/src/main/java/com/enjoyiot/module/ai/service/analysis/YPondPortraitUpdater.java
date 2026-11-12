package com.enjoyiot.module.ai.service.analysis;

import cn.hutool.core.util.StrUtil;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YFarmerHabitLibraryDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondFixedParamsDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondRuleLibraryDO;
import com.enjoyiot.module.ai.dal.mysql.analysis.YFarmerHabitLibraryMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YPondFixedParamsMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YPondRuleLibraryMapper;
import com.enjoyiot.module.ai.dto.analysis.YAiOutput;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 塘口画像回写器
 *
 * <p>将 AI 输出中的回填数据（新规律/新习惯/固定参数调整）回写到画像库：
 * y_pond_rule_library / y_farmer_habit_library / y_pond_fixed_params。</p>
 *
 * <p>回写原则：</p>
 * <ul>
 *   <li>更新塘口规律：直接插入新的塘口规律到塘口中（不做查重合并与权重更新，规律合并、权重更新属于 AI 二次分析，暂未开发）；</li>
 *   <li>更新农户习惯：直接插入新的农户习惯到农户习惯库中；</li>
 *   <li>更新塘口静态字段：按 AI 返回的字段 upsert 固定参数。</li>
 * </ul>
 *
 * <p>字段来源约定：更新时间（createTime/updateTime）由框架自动填充；
 * 需 AI 给出的字段已在提示词中明确要求，AI 未给出时由系统兜底默认值（如可信度默认值）。</p>
 */
@Slf4j
@Component
public class YPondPortraitUpdater {

    /** 默认规律可信度（AI 未给出时兜底） */
    private static final int DEFAULT_RULE_CREDIBILITY = 80;
    /** 默认习惯可信度（AI 未给出时兜底） */
    private static final int DEFAULT_HABIT_CREDIBILITY = 70;

    /** 固定参数中的字符串字段（范围类描述字段，非数值），AI 可调整 */
    private static final Set<String> STRING_FIELD_KEYS = Set.of(
            "transparencyRange", "feedingStrategyBase", "chlARange", "conductivityRange");

    @Resource
    private YPondRuleLibraryMapper pondRuleLibraryMapper;
    @Resource
    private YFarmerHabitLibraryMapper farmerHabitLibraryMapper;
    @Resource
    private YPondFixedParamsMapper pondFixedParamsMapper;

    /**
     * 回写方法①：更新塘口规律 —— 直接插入新的塘口规律到塘口中（y_pond_rule_library）
     *
     * <p>AI 给出：ruleTitle（必填）/ ruleContent / credibility（缺省 80）</p>
     * <p>系统自填：pondId / verifyCount=0 / invalidCount=0 / createTime、updateTime</p>
     *
     * @param pondId 塘口ID
     * @param update AI 输出的画像更新
     */
    @Transactional(rollbackFor = Exception.class)
    public void backfillPondRules(Long pondId, YAiOutput.PortraitUpdate update) {
        if (update == null || update.getRules() == null) {
            return;
        }
        for (YAiOutput.RuleItem rule : update.getRules()) {
            if (rule == null || StrUtil.isBlank(rule.getRuleTitle())) {
                continue;
            }
            YPondRuleLibraryDO ruleDO = new YPondRuleLibraryDO();
            ruleDO.setPondId(pondId);
            ruleDO.setRuleTitle(rule.getRuleTitle());
            ruleDO.setRuleContent(rule.getRuleContent());
            ruleDO.setCredibility(rule.getCredibility() == null ? DEFAULT_RULE_CREDIBILITY : rule.getCredibility());
            ruleDO.setVerifyCount(0);
            ruleDO.setInvalidCount(0);
            pondRuleLibraryMapper.insert(ruleDO);
        }
    }

    /**
     * 回写方法②：更新塘口静态字段 —— 按 AI 返回的字段 upsert 固定参数（y_pond_fixed_params）
     *
     * <p>AI 给出：fixedParams Map（key 为字段名，数值字段 + 范围/基准等字符串字段均可调整）</p>
     * <p>系统自填：pondId / createTime、updateTime</p>
     *
     * @param pondId 塘口ID
     * @param update AI 输出的画像更新
     */
    @Transactional(rollbackFor = Exception.class)
    public void backfillFixedParams(Long pondId, YAiOutput.PortraitUpdate update) {
        if (update == null || update.getFixedParams() == null || update.getFixedParams().isEmpty()) {
            return;
        }
        updateFixedParams(pondId, update.getFixedParams());
    }

    /**
     * 回写方法③：更新农户习惯 —— 直接插入新的农户习惯到农户习惯库中（y_farmer_habit_library）
     *
     * <p>AI 给出：habitTitle（必填）/ habitDetail / habitTendency / impact / impactNote / credibility（缺省 70）</p>
     * <p>系统自填：pondId / occurrenceCount=1 / createTime、updateTime</p>
     *
     * @param pondId 塘口ID
     * @param update AI 输出的画像更新
     */
    @Transactional(rollbackFor = Exception.class)
    public void backfillFarmerHabits(Long pondId, YAiOutput.PortraitUpdate update) {
        if (update == null || update.getHabits() == null) {
            return;
        }
        for (YAiOutput.HabitItem habit : update.getHabits()) {
            if (habit == null || StrUtil.isBlank(habit.getHabitTitle())) {
                continue;
            }
            YFarmerHabitLibraryDO habitDO = new YFarmerHabitLibraryDO();
            habitDO.setPondId(pondId);
            habitDO.setHabitTitle(habit.getHabitTitle());
            habitDO.setHabitDetail(habit.getHabitDetail());
            habitDO.setHabitTendency(habit.getHabitTendency());
            habitDO.setImpact(habit.getImpact());
            habitDO.setImpactNote(habit.getImpactNote());
            habitDO.setOccurrenceCount(1);
            habitDO.setCredibility(habit.getCredibility() == null ? DEFAULT_HABIT_CREDIBILITY : habit.getCredibility());
            farmerHabitLibraryMapper.insert(habitDO);
        }
    }

    /**
     * 更新固定参数（字段名映射 + upsert）
     */
    private void updateFixedParams(Long pondId, Map<String, Object> params) {
        YPondFixedParamsDO fixedParams = pondFixedParamsMapper.selectByPondId(pondId);
        boolean create = fixedParams == null;
        if (create) {
            fixedParams = new YPondFixedParamsDO();
            fixedParams.setPondId(pondId);
        }
        boolean changed = false;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (STRING_FIELD_KEYS.contains(key)) {
                // 字符串字段（范围/基准描述）
                String strValue = toStringValue(value);
                if (strValue == null) {
                    continue;
                }
                switch (key) {
                    case "transparencyRange":
                        fixedParams.setTransparencyRange(strValue);
                        break;
                    case "feedingStrategyBase":
                        fixedParams.setFeedingStrategyBase(strValue);
                        break;
                    case "chlARange":
                        fixedParams.setChlARange(strValue);
                        break;
                    case "conductivityRange":
                        fixedParams.setConductivityRange(strValue);
                        break;
                    default:
                        log.warn("[YPondPortraitUpdater][updateFixedParams] 未识别的字符串固定参数字段: {}", key);
                }
                changed = true;
                continue;
            }
            // 数值字段
            BigDecimal decimalValue = toBigDecimal(value);
            if (decimalValue == null) {
                continue;
            }
            switch (key) {
                case "maxLoadKg":
                    fixedParams.setMaxLoadKg(decimalValue);
                    break;
                case "phMin":
                    fixedParams.setPhMin(decimalValue);
                    break;
                case "phMax":
                    fixedParams.setPhMax(decimalValue);
                    break;
                case "dissolvedOxygenMin":
                    fixedParams.setDissolvedOxygenMin(decimalValue);
                    break;
                case "dissolvedOxygenMax":
                    fixedParams.setDissolvedOxygenMax(decimalValue);
                    break;
                case "ammoniaNitrogenMax":
                    fixedParams.setAmmoniaNitrogenMax(decimalValue);
                    break;
                case "nitriteMax":
                    fixedParams.setNitriteMax(decimalValue);
                    break;
                case "tempMin":
                    fixedParams.setTempMin(decimalValue);
                    break;
                case "tempMax":
                    fixedParams.setTempMax(decimalValue);
                    break;
                case "phDailyFluctMax":
                    fixedParams.setPhDailyFluctMax(decimalValue);
                    break;
                default:
                    log.warn("[YPondPortraitUpdater][updateFixedParams] 未识别的固定参数字段: {}", key);
            }
            changed = true;
        }
        if (changed) {
            if (create) {
                pondFixedParamsMapper.insert(fixedParams);
            } else {
                pondFixedParamsMapper.updateById(fixedParams);
            }
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            if (value instanceof Number) {
                return BigDecimal.valueOf(((Number) value).doubleValue());
            }
            return new BigDecimal(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

}
