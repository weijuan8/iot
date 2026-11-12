package com.enjoyiot.module.ai.service.analysis;

import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_DO;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_DO_NAME;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_NH;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_NH_NAME;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_PH;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_PH_NAME;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_TEMP;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.PROP_TEMP_NAME;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.UNIT_CELSIUS;
import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.UNIT_MG_L;

import com.enjoyiot.eiot.temporal.td.config.Constants;
import com.enjoyiot.eiot.temporal.td.dao.TdTemplate;
import com.enjoyiot.module.ai.dto.analysis.YPropertyDailyStat;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TDengine 属性按日聚合查询（SQL 下推聚合）
 *
 * <p>替代原 {@code YPondDataCollector.aggregate} 的 Java 逻辑层逐点聚合：
 * 直接使用 TDengine {@code INTERVAL(1d)} 窗口聚合，一次查出每日
 * min / max / avg / latest / count，避免回传海量原始点再在 JVM 内计算。</p>
 *
 * <p>约定：复用 module-eiot 的 {@link TdTemplate}（Bean 名 tdJdbcTemplate）与
 * {@link Constants}，不修改时序库原实现代码；本项目时序库固定为 TDengine。</p>
 */
@Slf4j
@Component
public class YDevicePropertyDailyStatQuery {

    @Resource(name = "tdJdbcTemplate")
    private TdTemplate tdTemplate;

    @Resource
    private DeviceApi deviceApi;

    /**
     * 查询指定设备属性近 N 天的每日统计（TDengine INTERVAL(1d) 下推聚合）
     *
     * @param deviceId    设备 ID
     * @param deviceName  设备名称（仅透传到结果）
     * @param propertyKey 属性标识符（物模型 identifier，如 DO/pH/temp/NH）
     * @param start       起始时间（毫秒）
     * @param end         结束时间（毫秒）
     * @return 每日统计列表（无数据返回空列表）
     */
    public List<YPropertyDailyStat> queryDailyStats(Long deviceId, String deviceName,
                                                    String propertyKey, long start, long end) {
        DeviceInfo device = deviceApi.getDeviceInfoFromCache(deviceId);
        if (device == null) {
            return new ArrayList<>();
        }
        // 仅查询数值型属性：version 等字符串/对象属性 TDengine 不支持 avg/min/max 聚合，直接跳过
        Map<String, DevicePropertyCache> lastProps = deviceApi.getPropertiesFromCache(deviceId);
        DevicePropertyCache prop = lastProps == null ? null : lastProps.get(propertyKey);
        if (prop == null || !(prop.getValue() instanceof Number)) {
            return new ArrayList<>();
        }
        String tableName = Constants.getProductPropertySTableName(device.getProductKey());
        // 嵌套属性（含 "."）用反引号包裹列名，与 IDevicePropertyData 原实现保持一致
        String fieldName = (propertyKey.contains(".") ? "`" + propertyKey + "`" : propertyKey).toLowerCase();
        String sql = String.format(
                "select _wstart as time,avg(%s) as avg_value,min(%s) as min_value,max(%s) as max_value,"
                        + "last(%s) as latest_value,count(%s) as count_value from %s "
                        + "where device_id=? and time>=? and time<=? partition by device_id interval(1d)",
                fieldName, fieldName, fieldName, fieldName, fieldName, tableName);
        try {
            List<DailyStatRow> rows = tdTemplate.query(sql,
                    new BeanPropertyRowMapper<>(DailyStatRow.class), deviceId, start, end);
            List<YPropertyDailyStat> stats = new ArrayList<>(rows.size());
            for (DailyStatRow row : rows) {
                if (row.getTime() == null || row.getCountValue() == null || row.getCountValue() == 0) {
                    continue;
                }
                YPropertyDailyStat stat = new YPropertyDailyStat();
                stat.setDeviceId(deviceId);
                stat.setDeviceName(deviceName);
                stat.setPropertyKey(propertyKey);
                stat.setPropertyName(resolvePropertyName(propertyKey));
                stat.setUnit(resolvePropertyUnit(propertyKey));
                stat.setDate(LocalDate.ofInstant(Instant.ofEpochMilli(row.getTime()), ZoneId.systemDefault()));
                stat.setMin(row.getMinValue());
                stat.setMax(row.getMaxValue());
                stat.setAvg(row.getAvgValue());
                stat.setLatest(row.getLatestValue());
                stat.setCount(row.getCountValue().intValue());
                // sum 仅内部兜底（提示词不使用），用 avg*count 估算
                if (row.getAvgValue() != null) {
                    stat.setSum(row.getAvgValue().multiply(BigDecimal.valueOf(row.getCountValue())));
                }
                stats.add(stat);
            }
            return stats;
        } catch (Exception e) {
            log.warn("[YDevicePropertyDailyStatQuery][queryDailyStats] TDengine 按日聚合查询失败, deviceId: {}, key: {}",
                    deviceId, propertyKey, e);
            return new ArrayList<>();
        }
    }

    /**
     * 属性中文名解析
     */
    private String resolvePropertyName(String key) {
        switch (key) {
            case PROP_DO: return PROP_DO_NAME;
            case PROP_PH: return PROP_PH_NAME;
            case PROP_TEMP: return PROP_TEMP_NAME;
            case PROP_NH: return PROP_NH_NAME;
            case "nitrite": return "亚硝酸盐";
            case "chl_a": return "叶绿素a";
            case "transparency": return "透明度";
            case "conductivity": return "电导率";
            default: return key;
        }
    }

    /**
     * 属性单位解析
     */
    private String resolvePropertyUnit(String key) {
        switch (key) {
            case PROP_DO: return UNIT_MG_L;
            case PROP_TEMP: return UNIT_CELSIUS;
            case PROP_NH: return UNIT_MG_L;
            case "nitrite": return UNIT_MG_L;
            case "chl_a": return "μg/L";
            case "conductivity": return "μS/cm";
            case "transparency": return "cm";
            default: return "";
        }
    }

    /**
     * TDengine INTERVAL(1d) 查询结果行
     */
    @Data
    static class DailyStatRow {

        /** 窗口起始时间（毫秒时间戳，即当天 00:00） */
        private Long time;

        private BigDecimal avgValue;

        private BigDecimal minValue;

        private BigDecimal maxValue;

        private BigDecimal latestValue;

        private Long countValue;

    }

}
