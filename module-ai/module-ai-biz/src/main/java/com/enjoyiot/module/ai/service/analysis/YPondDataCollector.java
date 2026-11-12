package com.enjoyiot.module.ai.service.analysis;

import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.*;

import cn.hutool.core.util.StrUtil;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YDailyAnalysisResultDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YFarmerHabitLibraryDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondFixedParamsDO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondRuleLibraryDO;
import com.enjoyiot.module.ai.dal.mysql.analysis.YDailyAnalysisResultMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YFarmerHabitLibraryMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YPondFixedParamsMapper;
import com.enjoyiot.module.ai.dal.mysql.analysis.YPondRuleLibraryMapper;
import com.enjoyiot.module.ai.dto.analysis.YPondAnalysisInput;
import com.enjoyiot.module.ai.dto.analysis.YPondDeviceData;
import com.enjoyiot.module.ai.dto.analysis.YPropertyDailyStat;
import com.enjoyiot.module.ai.dto.analysis.YPropertySeriesPoint;
import com.enjoyiot.module.ai.dto.analysis.YPropertySwitchSegment;
import com.enjoyiot.module.ai.dto.analysis.YesterdayAdvice;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YFarmDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDeviceDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YPondDO;
import com.enjoyiot.module.eiot.dal.dataobject.report.YAquacultureReportDO;
import com.enjoyiot.module.eiot.dal.mysql.farm.YFarmMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YModuleDeviceMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YPondMapper;
import com.enjoyiot.module.eiot.dal.mysql.report.YAquacultureReportMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 塘口数据汇总器
 *
 * 根据塘口ID汇总各类数据到一个 YPondAnalysisInput 实体中，投喂给 AI：
 * 塘口基本信息 + 塘口画像（固定参数/规律/习惯/昨日建议） + 设备数据（近N天属性聚合） + 上报数据
 */
@Slf4j
@Component
public class YPondDataCollector {

    @Resource
    private YPondMapper pondMapper;
    @Resource
    private YFarmMapper farmMapper;
    @Resource
    private YModuleDeviceMapper moduleDeviceMapper;
    @Resource
    private YAquacultureReportMapper aquacultureReportMapper;
    @Resource
    private YPondFixedParamsMapper pondFixedParamsMapper;
    @Resource
    private YPondRuleLibraryMapper pondRuleLibraryMapper;
    @Resource
    private YFarmerHabitLibraryMapper farmerHabitLibraryMapper;
    @Resource
    private YDailyAnalysisResultMapper dailyAnalysisResultMapper;
    @Resource
    private DeviceApi deviceApi;
    @Resource
    private YDevicePropertyDailyStatQuery dailyStatQuery;
    @Resource
    private YDevicePropertySeriesQuery seriesQuery;

    /**
     * 汇总塘口数据
     *
     * @param pondId 塘口ID
     * @return 汇总实体；塘口不存在返回 null
     */
    public YPondAnalysisInput collect(Long pondId) {
        YPondDO pond = pondMapper.selectById(pondId);
        if (pond == null) {
            log.warn("[YPondDataCollector][collect] 塘口不存在 pondId: {}", pondId);
            return null;
        }

        YPondAnalysisInput input = new YPondAnalysisInput();
        // ========== 塘口基本信息 ==========
        input.setPondId(pond.getId());
        input.setPondName(pond.getPondName());
        input.setPondCode(pond.getPondCode());
        input.setFarmId(pond.getFarmId());
        input.setPondType(pond.getPondType());
        input.setAreaMu(pond.getAreaMu() == null ? null : pond.getAreaMu().stripTrailingZeros().toPlainString());
        input.setStatus(pond.getStatus());
        YFarmDO farm = farmMapper.selectById(pond.getFarmId());
        if (farm != null) {
            input.setFarmName(farm.getFarmName());
        }

        // ========== 塘口画像 ==========
        input.setFixedParams(pondFixedParamsMapper.selectByPondId(pondId));
        // 规律最多取可信度最高的前 N 条投喂 AI，避免规律过多稀释 prompt
        input.setRules(pondRuleLibraryMapper.selectTopListByPondId(pondId, RULE_QUERY_LIMIT));
        // 农户习惯最多取可信度最高的前 N 条，避免习惯无限膨胀稀释 prompt
        input.setHabits(farmerHabitLibraryMapper.selectTopListByPondId(pondId, HABIT_QUERY_LIMIT));
        // 昨日分析数据：仅抽取昨日建议摘要（adviceContent/adviceCredibility），避免把主表系统字段泄入 prompt
        input.setYesterdayResult(toYesterdayAdvice(dailyAnalysisResultMapper.selectLatestBeforeDate(pondId, LocalDate.now())));

        // ========== 设备数据（近 24 小时时序 + 开关段） ==========
        input.setDevices(collectDevices(pondId));

        // ========== 上报数据（昨日 + 今日） ==========
        input.setReportSummary(collectReportSummary(pondId));

        return input;
    }

    /**
     * 汇总塘口设备日聚合数据（首页实时展示用）
     *
     * 仅查 TDengine 属性按日聚合（INTERVAL(1d)）与最新属性值，
     * 不做 1 小时序列/开关段查询（避免首页实时渲染过重）。
     *
     * @param pondId 塘口ID
     * @param days   聚合天数（首页用 HOME_STAT_LOOKBACK_DAYS=3 天回退容错）
     * @return 设备日聚合数据列表（仅含 dailyStats/lastProperties）
     */
    public List<YPondDeviceData> collectDailyStats(Long pondId, int days) {
        List<YPondDeviceData> result = new ArrayList<>();
        List<YModuleDeviceDO> moduleDevices = moduleDeviceMapper.selectList(
                new LambdaQueryWrapperX<YModuleDeviceDO>().eq(YModuleDeviceDO::getPondId, pondId));
        if (moduleDevices.isEmpty()) {
            return result;
        }
        long end = System.currentTimeMillis();
        long start = end - days * 24L * 3600 * 1000;
        for (YModuleDeviceDO md : moduleDevices) {
            try {
                DeviceInfo deviceInfo = null;
                Map<String, DevicePropertyCache> lastProps = null;
                try {
                    deviceInfo = deviceApi.getDeviceInfoFromCache(md.getDeviceId());
                } catch (Exception e) {
                    log.warn("[YPondDataCollector][collectDailyStats] 设备信息获取失败 deviceId: {}", md.getDeviceId(), e);
                }
                try {
                    lastProps = deviceApi.getPropertiesFromCache(md.getDeviceId());
                } catch (Exception e) {
                    log.warn("[YPondDataCollector][collectDailyStats] 设备属性缓存获取失败 deviceId: {}", md.getDeviceId(), e);
                }
                if (deviceInfo == null && lastProps == null) {
                    continue;
                }
                YPondDeviceData deviceData = new YPondDeviceData();
                deviceData.setDeviceId(md.getDeviceId());
                if (deviceInfo != null) {
                    deviceData.setDeviceName(deviceInfo.getName());
                    deviceData.setDn(deviceInfo.getDn());
                    deviceData.setProductKey(deviceInfo.getProductKey());
                }
                // 产品分析白名单：未配置分析参数的产品（太阳能/摄像头等）不参与首页统计，
                // 与 AI 分析链路 collectDevice 口径一致——静态文件规定查哪个产品、哪个参数，就只查哪些。
                String productKey = deviceData.getProductKey();
                List<String> analyzeProps = productKey == null ? null : POND_ANALYSIS_PRODUCT_PROPERTIES.get(productKey);
                if (analyzeProps == null || analyzeProps.isEmpty()) {
                    log.info("[YPondDataCollector][collectDailyStats] 产品未配置分析参数，跳过设备 deviceId: {}, productKey: {}", md.getDeviceId(), productKey);
                    continue;
                }
                // 最新属性值：仅取白名单内且设备已上传的属性（version 等非白名单属性不采集、不分析）
                Map<String, Object> lastValues = new HashMap<>();
                if (lastProps != null) {
                    for (String key : analyzeProps) {
                        DevicePropertyCache prop = lastProps.get(key);
                        if (prop != null && prop.getValue() != null) {
                            lastValues.put(key, prop.getValue());
                        }
                    }
                }
                deviceData.setLastProperties(lastValues);
                List<YPropertyDailyStat> dailyStats = new ArrayList<>();
                for (String key : lastValues.keySet()) {
                    // 仅对数值型属性做按日聚合：version 等字符串/对象属性（如固件版本号）TDengine 不支持 avg/min/max，跳过
                    Object value = lastValues.get(key);
                    if (!(value instanceof Number)) {
                        continue;
                    }
                    dailyStats.addAll(dailyStatQuery.queryDailyStats(md.getDeviceId(), deviceData.getDeviceName(), key, start, end));
                }
                deviceData.setDailyStats(dailyStats);
                result.add(deviceData);
            } catch (Exception e) {
                log.warn("[YPondDataCollector][collectDailyStats] 设备日聚合失败 deviceId: {}", md.getDeviceId(), e);
            }
        }
        return result;
    }

    /**
     * 抽取昨日建议摘要（adviceContent/adviceCredibility），供 AI 复盘使用
     */
    private YesterdayAdvice toYesterdayAdvice(YDailyAnalysisResultDO result) {
        if (result == null) {
            return null;
        }
        YesterdayAdvice advice = new YesterdayAdvice();
        advice.setAdviceContent(result.getAdviceContent());
        advice.setAdviceCredibility(result.getAdviceCredibility());
        return advice;
    }

    /**
     * 汇总塘口设备数据
     */
    private List<YPondDeviceData> collectDevices(Long pondId) {
        List<YPondDeviceData> result = new ArrayList<>();
        List<YModuleDeviceDO> moduleDevices = moduleDeviceMapper.selectList(
                new LambdaQueryWrapperX<YModuleDeviceDO>().eq(YModuleDeviceDO::getPondId, pondId));
        if (moduleDevices.isEmpty()) {
            return result;
        }

        // 直接按分析周期（近 DEVICE_SERIES_PERIOD_HOURS 小时）作为查询窗口，边界差半天一天不影响聚合结果
        long end = System.currentTimeMillis();
        long start = end - DEVICE_SERIES_PERIOD_HOURS * 3600 * 1000L;

        for (YModuleDeviceDO md : moduleDevices) {
            try {
                YPondDeviceData deviceData = collectDevice(md.getDeviceId(), start, end);
                if (deviceData != null) {
                    result.add(deviceData);
                }
            } catch (Exception e) {
                log.warn("[YPondDataCollector][collectDevices] 设备数据汇总失败 deviceId: {}", md.getDeviceId(), e);
            }
        }
        return result;
    }

    /**
     * 汇总单个设备数据
     *
     * 按产品分析白名单 {@link YAiAnalysisConstants#POND_ANALYSIS_PRODUCT_PROPERTIES} 过滤：
     * - 设备 productKey 未配置分析参数（如太阳能、摄像头等）→ 不参与分析，返回 null；
     * - 已配置 → 仅采集配置的属性；配置了但设备未上传的属性直接跳过（不采集、不报错）。
     */
    private YPondDeviceData collectDevice(Long deviceId, long start, long end) {
        DeviceInfo deviceInfo = null;
        Map<String, DevicePropertyCache> lastProps = null;
        try {
            deviceInfo = deviceApi.getDeviceInfoFromCache(deviceId);
        } catch (Exception e) {
            log.warn("[YPondDataCollector][collectDevice] 设备信息获取失败 deviceId: {}", deviceId, e);
        }
        try {
            lastProps = deviceApi.getPropertiesFromCache(deviceId);
        } catch (Exception e) {
            log.warn("[YPondDataCollector][collectDevice] 设备属性缓存获取失败 deviceId: {}", deviceId, e);
        }
        if (deviceInfo == null && lastProps == null) {
            return null;
        }

        // 产品分析白名单：未配置分析参数的产品（太阳能/摄像头等）不参与塘口分析
        String productKey = deviceInfo == null ? null : deviceInfo.getProductKey();
        List<String> analyzeProps = productKey == null ? null : POND_ANALYSIS_PRODUCT_PROPERTIES.get(productKey);
        if (analyzeProps == null || analyzeProps.isEmpty()) {
            log.info("[YPondDataCollector][collectDevice] 产品未配置分析参数，跳过设备 deviceId: {}, productKey: {}", deviceId, productKey);
            return null;
        }

        YPondDeviceData deviceData = new YPondDeviceData();
        deviceData.setDeviceId(deviceId);
        if (deviceInfo != null) {
            deviceData.setDeviceName(deviceInfo.getName());
            deviceData.setDn(deviceInfo.getDn());
            deviceData.setProductKey(productKey);
        }
        // 最新属性值：仅取白名单内且设备已上传的属性；缺失参数直接跳过（不采集、不报错）
        Map<String, Object> lastValues = new HashMap<>();
        for (String key : analyzeProps) {
            DevicePropertyCache prop = lastProps == null ? null : lastProps.get(key);
            if (prop != null && prop.getValue() != null) {
                lastValues.put(key, prop.getValue());
            }
        }
        deviceData.setLastProperties(lastValues);

        // 属性 1 小时时间序列（TDengine INTERVAL(1h) 下推）：连续量按小时点、开关量合并为状态时间段，投喂 AI
        // 仅查设备已上传的白名单属性，避免对未上传属性发起 TDengine 查询（列可能不存在）
        List<YPropertySeriesPoint> series = new ArrayList<>();
        List<YPropertySwitchSegment> switchSegments = new ArrayList<>();
        for (String key : lastValues.keySet()) {
            List<YPropertySeriesPoint> points = seriesQuery.queryPropertySeries(deviceId, key, start, end);
            if (points.isEmpty()) {
                continue;
            }
            if (seriesQuery.isSwitchProperty(points)) {
                // 开关量（值域 0/1）：相邻相同状态合并为一个时间段，如 02:00-04:00 开
                switchSegments.addAll(seriesQuery.buildSwitchSegments(key, points));
            } else {
                // 连续量：按 1 小时序列点投喂，保留趋势
                series.addAll(points);
            }
        }
        deviceData.setPropertySeries(series);
        deviceData.setSwitchSegments(switchSegments);

        // 属性按日聚合（TDengine INTERVAL(1d) 下推，仅供首页展示，不投喂 AI）
        List<YPropertyDailyStat> dailyStats = new ArrayList<>();
        for (String key : lastValues.keySet()) {
            dailyStats.addAll(dailyStatQuery.queryDailyStats(deviceId, deviceData.getDeviceName(), key, start, end));
        }
        deviceData.setDailyStats(dailyStats);
        return deviceData;
    }

    /**
     * 汇总昨日 + 今日上报数据为文本摘要
     */
    private String collectReportSummary(Long pondId) {
        LocalDateTime startTime = LocalDate.now().minusDays(REPORT_SUMMARY_PERIOD_DAYS - 1L).atStartOfDay();
        List<YAquacultureReportDO> reports = aquacultureReportMapper.selectList(
                new LambdaQueryWrapperX<YAquacultureReportDO>()
                        .eq(YAquacultureReportDO::getPondId, pondId)
                        .ge(YAquacultureReportDO::getReportTime, startTime)
                        .orderByAsc(YAquacultureReportDO::getReportTime));
        if (reports.isEmpty()) {
            return "近" + REPORT_SUMMARY_PERIOD_DAYS + "天无巡塘上报记录";
        }
        StringBuilder sb = new StringBuilder();
        for (YAquacultureReportDO r : reports) {
            sb.append("【").append(r.getReportTime()).append("】");
            sb.append("操作人:").append(StrUtil.blankToDefault(r.getOperatorName(), "-"));
            sb.append("，投喂:").append(Boolean.TRUE.equals(r.getFeedingEnabled()) ? "是" : "否");
            if (r.getFeedingAmountKg() != null) {
                sb.append("，投喂量:").append(r.getFeedingAmountKg().stripTrailingZeros().toPlainString()).append("kg");
            }
            sb.append("，摄食:").append(StrUtil.blankToDefault(r.getFeedingStatus(), "-"));
            sb.append("，换水:").append(Boolean.TRUE.equals(r.getWaterChange()) ? "是" : "否");
            if (StrUtil.isNotBlank(r.getWaterRatio())) {
                sb.append("(").append(r.getWaterRatio()).append(")");
            }
            sb.append("，浮头:").append(StrUtil.blankToDefault(r.getFloatingHead(), "none"));
            if (r.getDeathCount() != null && r.getDeathCount() > 0) {
                sb.append("，死亡:").append(r.getDeathCount()).append("条");
            }
            sb.append("，水色:").append(StrUtil.blankToDefault(r.getWaterColor(), "-"));
            sb.append("，病害:").append(Boolean.TRUE.equals(r.getDisease()) ? "是" : "否");
            if (StrUtil.isNotBlank(r.getRemark())) {
                sb.append("，备注:").append(r.getRemark());
            }
            sb.append("；");
        }
        return sb.toString();
    }

}
