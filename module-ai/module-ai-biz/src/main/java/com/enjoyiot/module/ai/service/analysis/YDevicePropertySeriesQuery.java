package com.enjoyiot.module.ai.service.analysis;

import com.enjoyiot.eiot.temporal.td.config.Constants;
import com.enjoyiot.eiot.temporal.td.dao.TdTemplate;
import com.enjoyiot.module.ai.dto.analysis.YPropertySeriesPoint;
import com.enjoyiot.module.ai.dto.analysis.YPropertySwitchSegment;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * TDengine 属性时间序列查询（1 小时降采样，SQL 下推）
 *
 * <p>设备按 1~5 分钟上报一次，24 小时原始点可达数百条，直接投喂 AI 过大。
 * 这里用 TDengine {@code INTERVAL(1h)} 窗口聚合一次查出时间序列，
 * 每个 1 小时窗口取最近一次上报值（last）作为该数据点的代表值。</p>
 *
 * <p>属性分两类投喂给 AI：</p>
 * <ul>
 *   <li>连续量（溶氧/水温/氨氮等）：按 1 小时序列点给出，AI 分析趋势与缺氧时段；</li>
 *   <li>开关量（0/1，如氧机开关）：按状态时间段给出，相邻相同状态合并为一段
 *       （如"02:00-04:00 开"），一整天全开会合并成 1 段，大幅压缩数据量。</li>
 * </ul>
 *
 * <p>约定：复用 module-eiot 的 {@link TdTemplate}（Bean 名 tdJdbcTemplate）与
 * {@link Constants}，不修改时序库原实现代码；本项目时序库固定为 TDengine。</p>
 */
@Slf4j
@Component
public class YDevicePropertySeriesQuery {

    @Resource(name = "tdJdbcTemplate")
    private TdTemplate tdTemplate;

    @Resource
    private DeviceApi deviceApi;

    /**
     * 查询指定设备属性近 N 天的 1 小时时间序列（连续量，TDengine INTERVAL(1h) 下推聚合）
     *
     * @param deviceId    设备 ID
     * @param propertyKey 属性标识符（物模型 identifier，如 DO/pH/temp/NH）
     * @param start       起始时间（毫秒）
     * @param end         结束时间（毫秒）
     * @return 时间序列点列表（按时间升序，无数据返回空列表）
     */
    public List<YPropertySeriesPoint> queryPropertySeries(Long deviceId, String propertyKey,
                                                          long start, long end) {
        List<SeriesRow> rows = queryHourlyRows(deviceId, propertyKey, start, end);
        List<YPropertySeriesPoint> points = new ArrayList<>(rows.size());
        for (SeriesRow row : rows) {
            if (row.getTime() == null || row.getValue() == null) {
                continue;
            }
            YPropertySeriesPoint point = new YPropertySeriesPoint();
            point.setPropertyKey(propertyKey);
            point.setTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(row.getTime()), ZoneId.systemDefault()));
            point.setValue(row.getValue());
            points.add(point);
        }
        return points;
    }

    /**
     * 判断属性是否为开关量（值域全为 0/1）
     *
     * @param points 1 小时序列点（由 {@link #queryPropertySeries} 返回）
     * @return 全部值均为 0 或 1 时返回 true；空列表返回 false
     */
    public boolean isSwitchProperty(List<YPropertySeriesPoint> points) {
        if (points == null || points.isEmpty()) {
            return false;
        }
        for (YPropertySeriesPoint point : points) {
            BigDecimal v = point.getValue();
            if (v == null || (v.compareTo(BigDecimal.ZERO) != 0 && v.compareTo(BigDecimal.ONE) != 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将 1 小时序列点合并为开关状态时间段（相邻相同状态合并为一段）
     *
     * <p>示例：02:00=开、03:00=开、04:00=关、05:00=关 → 两段：
     * [02:00-04:00 开]、[04:00-06:00 关]。若一整天都是开，则只产生一个时间段。</p>
     *
     * @param propertyKey 属性标识符
     * @param points      1 小时序列点（按时间升序，由 {@link #queryPropertySeries} 返回）
     * @return 状态时间段列表（按时间升序）
     */
    public List<YPropertySwitchSegment> buildSwitchSegments(String propertyKey,
                                                            List<YPropertySeriesPoint> points) {
        List<YPropertySwitchSegment> segments = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return segments;
        }
        BigDecimal currentValue = null;
        LocalDateTime segmentStart = null;
        for (YPropertySeriesPoint point : points) {
            if (currentValue == null) {
                currentValue = point.getValue();
                segmentStart = point.getTime();
            } else if (currentValue.compareTo(point.getValue()) != 0) {
                segments.add(buildSegment(propertyKey, segmentStart, point.getTime(), currentValue));
                currentValue = point.getValue();
                segmentStart = point.getTime();
            }
        }
        if (currentValue != null) {
            // 最后一段结束时间 = 最后一个点所在窗口的结束（起始 + 1 小时）
            LocalDateTime segmentEnd = points.get(points.size() - 1).getTime().plusHours(1);
            segments.add(buildSegment(propertyKey, segmentStart, segmentEnd, currentValue));
        }
        return segments;
    }

    /**
     * 构造单个状态时间段
     */
    private YPropertySwitchSegment buildSegment(String propertyKey, LocalDateTime start,
                                                LocalDateTime end, BigDecimal value) {
        YPropertySwitchSegment segment = new YPropertySwitchSegment();
        segment.setPropertyKey(propertyKey);
        segment.setStartTime(start);
        segment.setEndTime(end);
        segment.setValue(value);
        return segment;
    }

    /**
     * 查询指定设备属性近 N 天的 1 小时窗口原始行（TDengine INTERVAL(1h) 下推聚合）
     */
    private List<SeriesRow> queryHourlyRows(Long deviceId, String propertyKey, long start, long end) {
        DeviceInfo device = deviceApi.getDeviceInfoFromCache(deviceId);
        if (device == null) {
            return new ArrayList<>();
        }
        String tableName = Constants.getProductPropertySTableName(device.getProductKey());
        // 嵌套属性（含 "."）用反引号包裹列名，与 IDevicePropertyData 原实现保持一致
        String fieldName = (propertyKey.contains(".") ? "`" + propertyKey + "`" : propertyKey).toLowerCase();
        String sql = String.format(
                // value 是 TDengine 保留字，别名需用反引号包裹（与 module-eiot DevicePropertyDataImpl 原实现一致）
                "select _wstart as time,last(%s) as `value` from %s "
                        + "where device_id=? and time>=? and time<=? partition by device_id interval(1h)",
                fieldName, tableName);
        try {
            return tdTemplate.query(sql,
                    new BeanPropertyRowMapper<>(SeriesRow.class), deviceId, start, end);
        } catch (Exception e) {
            log.warn("[YDevicePropertySeriesQuery][queryPropertySeries] TDengine 序列查询失败, deviceId: {}, key: {}",
                    deviceId, propertyKey, e);
            return new ArrayList<>();
        }
    }

    /**
     * TDengine INTERVAL(1h) 查询结果行
     */
    @Data
    static class SeriesRow {

        /** 窗口起始时间（毫秒时间戳，即每小时边界，如 03:00） */
        private Long time;

        /** 窗口内最近一次上报值 */
        private BigDecimal value;

    }

}
