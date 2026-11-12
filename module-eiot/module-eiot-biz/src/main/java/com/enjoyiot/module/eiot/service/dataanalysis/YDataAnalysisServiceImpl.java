/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot]
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */
package com.enjoyiot.module.eiot.service.dataanalysis;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.enjoyiot.eiot.temporal.td.config.Constants;
import com.enjoyiot.eiot.temporal.td.dao.TdTemplate;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisDeviceVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisExportReqVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisExportRespVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisPropertyVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisSeriesPointVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisSeriesRespVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisXLabelVO;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YModuleDeviceRespVO;
import com.enjoyiot.module.eiot.dal.redis.no.EiotRedisDAO;
import com.enjoyiot.module.eiot.enums.YDataAnalysisConstants;
import com.enjoyiot.module.eiot.service.farm.YModuleService;
import com.enjoyiot.module.eiot.service.product.ThingModelService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据分析 Service 实现（TDengine 时序聚合，统计/降采样均在 SQL 层面完成）
 * @author EnjoyIot
 */
@Service
@Slf4j
public class YDataAnalysisServiceImpl implements YDataAnalysisService {

    /**
     * 默认查询区间（24 小时）
     */
    private static final long DEFAULT_RANGE_MS = 24 * 60 * 60 * 1000L;

    /**
     * 默认降采样窗口（分钟）
     */
    private static final int DEFAULT_INTERVAL_MINUTES = 10;
    private static final int DEFAULT_MAX_POINTS = 200;

    /**
     * 时间格式化（前端展示 MM/dd HH:mm）
     */
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMATTER =
            ThreadLocal.withInitial(() -> new SimpleDateFormat(YDataAnalysisConstants.TIME_PATTERN));

    @Resource(name = "tdJdbcTemplate")
    private TdTemplate tdTemplate;

    @Resource
    private YModuleService moduleService;

    @Resource
    private ThingModelService thingModelService;

    @Resource
    private EiotRedisDAO eiotRedisDAO;

    @Override
    public List<YDataAnalysisDeviceVO> yGetProperties(Long moduleId) {
        // 1. 获取模块下设备
        List<YModuleDeviceRespVO> devices = moduleService.getModuleDevices(moduleId);
        if (CollUtil.isEmpty(devices)) {
            return new ArrayList<>();
        }
        // 2. 按设备分组：遍历设备物模型，筛选可分析的数值型属性（设备实际有上报数据）
        List<YDataAnalysisDeviceVO> result = new ArrayList<>();
        int colorIndex = 0;
        for (YModuleDeviceRespVO device : devices) {
            ThingModel thingModel = thingModelService.getThingModelByProductKeyFromCache(device.getDeviceType());
            if (thingModel == null || thingModel.getModel() == null
                    || CollUtil.isEmpty(thingModel.getModel().getProperties())) {
                continue;
            }
            Map<String, DevicePropertyCache> reportedProps = eiotRedisDAO.getProperties(device.getDeviceId());
            List<YDataAnalysisPropertyVO> props = new ArrayList<>();
            for (ThingModel.Property prop : thingModel.getModel().getProperties()) {
                String identifier = prop.getIdentifier();
                if (StrUtil.isBlank(identifier)) {
                    continue;
                }
                // 仅保留数值型属性（bool/string 等无法绘制趋势图）
                if (!yIsNumericProperty(prop)) {
                    continue;
                }
                // 仅保留设备实际有上报数据的属性
                if (reportedProps == null || !reportedProps.containsKey(identifier)) {
                    continue;
                }
                props.add(yBuildPropertyVO(prop, colorIndex++));
            }
            if (CollUtil.isNotEmpty(props)) {
                YDataAnalysisDeviceVO deviceVO = new YDataAnalysisDeviceVO();
                deviceVO.setDeviceId(device.getDeviceId());
                deviceVO.setDeviceName(device.getDeviceName());
                deviceVO.setProperties(props);
                result.add(deviceVO);
            }
        }
        return result;
    }

    @Override
    public YDataAnalysisSeriesRespVO yGetSeries(Long moduleId, String key, Long deviceId,
                                                Long startTs, Long endTs, Integer intervalMinutes,
                                                Integer maxPoints) {
        // 1. 时间范围与降采样窗口：优先使用调用方指定窗口；否则按目标点数推算，控制返回量
        long start = startTs != null ? startTs : System.currentTimeMillis() - DEFAULT_RANGE_MS;
        long end = endTs != null ? endTs : System.currentTimeMillis();
        int interval = intervalMinutes != null && intervalMinutes > 0
                ? intervalMinutes : yCalcIntervalMinutes(start, end, maxPoints);
        YDataAnalysisSeriesRespVO result = new YDataAnalysisSeriesRespVO();
        result.setPoints(new ArrayList<>());
        // 2. 只查指定设备：从模块设备列表中定位该设备，获取其产品物类型
        //    （TDengine 按产品建立属性超级表，需先知道 productType 才能确定表名）
        if (deviceId == null) {
            return result;
        }
        List<YModuleDeviceRespVO> devices = moduleService.getModuleDevices(moduleId);
        YModuleDeviceRespVO device = CollUtil.isNotEmpty(devices) ? devices.stream()
                .filter(d -> d.getDeviceId().equals(deviceId))
                .findFirst()
                .orElse(null) : null;
        if (device == null) {
            return result;
        }
        String tableName = Constants.getProductPropertySTableName(device.getDeviceType());
        String fieldName = yBuildFieldName(key);
        // 3. 查询该设备该属性的时序点（SQL 层面 INTERVAL 降采样）
        result.setPoints(yQuerySeries(tableName, fieldName, deviceId, start, end, interval));
        // 4. 生成 X 轴时间标签：由时间区间决定，与数据无关——某天没上报，
        //    日期标签照常返回，前端直接渲染，无需自行计算（避免前后端逻辑不一致）
        result.setXLabels(yBuildXLabels(start, end));
        return result;
    }

    /**
     * 生成 X 轴时间标签（前端直接渲染，数量/文本均由后端决定）：
     * <ul>
     *   <li>单日（跨度 ≤ 24h）：从 start 到 end 均匀取 7 个时刻，文本 HH:mm</li>
     *   <li>多日：按自然日每天 00:00 一个标签（天数 ≤ 8 全部显示，更多时均匀抽到约 7 个，
     *       且保证最后一天也在），文本 MM/dd</li>
     * </ul>
     * <p>标签数量上限固定 7：前端画布宽度有限（约 320px），7 个标签不会拥挤；
     * 画布宽度属前端渲染细节，后端按保守上限生成，前端直接使用。</p>
     */
    private List<YDataAnalysisXLabelVO> yBuildXLabels(long start, long end) {
        List<YDataAnalysisXLabelVO> labels = new ArrayList<>();
        SimpleDateFormat fmt = TIME_FORMATTER.get(); // MM/dd HH:mm（与点位 time 同源，时区一致）
        // 多日抽稀标签（等距整分钟）显示完整 MM/dd HH:mm；自然日标签（每天 00:00）只显示 MM/dd
        boolean showTime = false;
        // 1. 单日：均匀取 7 个时刻
        if (end - start <= 24 * 60 * 60 * 1000L) {
            long span = Math.max(1, end - start);
            for (int i = 0; i < 7; i++) {
                long ts = start + span * i / 6;
                YDataAnalysisXLabelVO v = new YDataAnalysisXLabelVO();
                v.setTs(ts);
                // 去掉 MM/dd 前缀，只留 HH:mm
                v.setText(fmt.format(new Date(ts)).substring(6));
                labels.add(v);
            }
            return labels;
        }
        // 2. 多日：按自然日生成（每天 00:00）
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(start);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        cal.setTimeInMillis(end);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayEnd = cal.getTimeInMillis();
        List<Long> days = new ArrayList<>();
        for (long d = dayStart; d <= dayEnd; ) {
            days.add(d);
            cal.setTimeInMillis(d);
            cal.add(Calendar.DAY_OF_YEAR, 1);
            d = cal.getTimeInMillis();
        }
        // 天数 ≤ 8 全显示：每天一个标签，最多 8 个，间隔恒定 1 天；
        // 超过 8 天时固定 8 个标签，在 [dayStart, dayEnd] 上按"整数分钟步长"精确等距：
        //   - 步长 = round(总分钟数 / 7)，ts 精确到分钟——按天分只有整数步长可选
        //     （9 天只能 5 个或 9 个），按分钟分取整误差 ≤0.5 分钟，像素上绝对均匀；
        //   - 最后一点强制 = dayEnd，末日（最新一天）精确显示；
        //   - 文本 = MM/dd HH:mm（ts 精确到分钟，日期+时间完整显示）；
        //   - 数量恒定 8 个
        if (days.size() > 8) {
            long totalMinutes = Math.max(1, (dayEnd - dayStart) / 60000);
            long step = Math.max(1, Math.round((double) totalMinutes / 7));
            List<Long> shown = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                shown.add(dayStart + step * 60_000L * i);
            }
            shown.add(dayEnd);
            days = shown;
            showTime = true;
        }
        for (long d : days) {
            YDataAnalysisXLabelVO v = new YDataAnalysisXLabelVO();
            v.setTs(d);
            // 抽稀标签带具体时刻 MM/dd HH:mm；自然日标签（每天 00:00）只显示 MM/dd
            v.setText(showTime ? fmt.format(new Date(d)) : fmt.format(new Date(d)).substring(0, 5));
            labels.add(v);
        }
        return labels;
    }

    /**
     * 根据时间范围与目标点数推算 INTERVAL 降采样窗口（分钟），保证返回窗口数不超过目标点数
     */
    private int yCalcIntervalMinutes(long start, long end, Integer maxPoints) {
        int points = maxPoints != null && maxPoints > 0 ? maxPoints : DEFAULT_MAX_POINTS;
        long totalMinutes = Math.max(1, (end - start) / 60000);
        return (int) Math.max(1, Math.ceil((double) totalMinutes / points));
    }

    /**
     * 是否为可绘制趋势图的数值型属性
     */
    private boolean yIsNumericProperty(ThingModel.Property prop) {
        if (prop.getDataType() == null || StrUtil.isBlank(prop.getDataType().getType())) {
            return false;
        }
        return YDataAnalysisConstants.NUMERIC_PROPERTY_TYPES.contains(prop.getDataType().normalizedType());
    }

    /**
     * 组装属性展示 VO（展示配置优先，兜底使用物模型名称/单位/规格阈值）
     */
    private YDataAnalysisPropertyVO yBuildPropertyVO(ThingModel.Property prop, int colorIndex) {
        YDataAnalysisPropertyVO vo = new YDataAnalysisPropertyVO();
        vo.setKey(prop.getIdentifier());
        vo.setLabel(prop.getName());
        vo.setUnit(prop.getUnit());
        vo.setIcon(prop.getIcon());
        YDataAnalysisConstants.YPropDisplayConfig display =
                YDataAnalysisConstants.getPropDisplay(prop.getIdentifier());
        if (display != null) {
            vo.setLabel(display.getLabel());
            vo.setUnit(display.getUnit());
            vo.setMin(display.getMinDecimal());
            vo.setMax(display.getMaxDecimal());
            vo.setColor(display.getColor());
        } else {
            vo.setColor(YDataAnalysisConstants.getColor(colorIndex));
            vo.setMin(yParseSpecDecimal(prop, "min"));
            vo.setMax(yParseSpecDecimal(prop, "max"));
        }
        return vo;
    }

    /**
     * 从物模型属性规格解析数值（min/max 等）
     */
    private BigDecimal yParseSpecDecimal(ThingModel.Property prop, String specKey) {
        if (prop.getDataType() == null) {
            return null;
        }
        Object value = prop.getDataType().getSpecMap().get(specKey);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 构建 TDengine 列名（物模型标识符小写，含 . 用反引号包裹）
     */
    private String yBuildFieldName(String key) {
        String fieldName = key.toLowerCase();
        return fieldName.contains(".") ? "`" + fieldName + "`" : fieldName;
    }

    /**
     * 查询单台设备单个属性的时序数据（TDengine INTERVAL 降采样）。
     *
     * <p>核心思路：不把原始数据全部查出，而是在 SQL 层面把时间轴按固定窗口
     * （interval 分钟）切段，每段只取该窗口内「最后一次上报的值」（last 聚合）。
     * 这样返回的数据量 = 窗口数（受 maxPoints 控制），而不是原始记录数。</p>
     *
     * <p>SQL 结构：</p>
     * <pre>
     * select last(time) as time,       -- 窗口内最后一次上报的真实时间戳（毫秒精度），即该窗口「最后一条记录的时刻」
     *        last(字段名) as `value`    -- 窗口内最后一次上报的属性值
     * from 超级表名                     -- 该产品物类型对应的属性超级表
     * where device_id = ?              -- 只查当前设备（TDengine 每个设备对应一条子表）
     *   and time &gt;= ? and time &lt;= ?    -- 时间范围过滤（? 为 JDBC 预编译参数）
     * interval(?m)                     -- 按 ? 分钟把时间轴切成一个个窗口
     * </pre>
     * <p>说明：time 用 last(time) 而不是 _wstart（窗口起点），是为了让数据点
     * 对应「真实上报时刻」而非对齐整分钟的窗口起点——设备按整包上报时，
     * last(time) 与 last(字段) 来自同一条记录，时间戳与值严格对应。</p>
     *
     * @param tableName TDengine 属性超级表名（由设备 deviceType 推算，见调用方）
     * @param fieldName 属性标识符列名（如 do，由物模型 key 小写处理后得到）
     * @param deviceId  设备编号，作为 device_id 查询条件
     * @param start     查询开始时间戳（毫秒）
     * @param end       查询结束时间戳（毫秒）
     * @param interval  降采样窗口大小（分钟），决定返回多少个数据点
     * @return 按时间升序的时序点列表（无数据或查询失败返回空列表）
     */
    private List<YDataAnalysisSeriesPointVO> yQuerySeries(String tableName, String fieldName, Long deviceId,
                                                          long start, long end, int interval) {
        // 1. 先定 x 轴点位（x 轴时间线优先）：
        //    从 start 起每 interval 分钟一个点位，均匀覆盖 [start, end]。
        //    点位只由「时间区间 + 窗口大小」决定，与数据无关——某天没上报，点位依然存在，
        //    只是 value 为 null，前端据此断线/留空，时间轴不会跳天。
        //    窗口数受 maxPoints 控制（约 60），循环开销可忽略。
        long step = interval * 60_000L;
        List<YDataAnalysisSeriesPointVO> points = new ArrayList<>();
        for (long w = start; w <= end; w += step) {
            YDataAnalysisSeriesPointVO p = new YDataAnalysisSeriesPointVO();
            p.setTs(w);
            p.setTime(TIME_FORMATTER.get().format(new Date(w)));
            p.setValue(null);
            points.add(p);
        }
        if (points.isEmpty()) {
            return points;
        }

        // 2. SQL 结构：
        //    select _wstart as wstart,last(time) as time,last(字段) as `value`
        //    from 超级表 where device_id=? and time>=? and time<=? interval(?m)
        //    - where 里的 ? 是 JDBC 预编译参数，由下方 tdTemplate.query 变参按顺序绑定
        //    - last(time)：窗口内最后一次上报的真实时刻（非窗口起点，时间戳对应真实数据）
        //    - _wstart：窗口起点，用于把窗口数据归位到点位（见第 3 步）
        //    - 空窗口（设备无上报）TDengine 不返回记录，点位保持 null
        String sql = String.format(
                "select _wstart as wstart,last(time) as time,last(%s) as `value` from %s where device_id=? and time>=? and time<=? interval(%dm)",
                fieldName, tableName, interval);
        try {
            // 3. 把窗口数据归位到最近点位：
            //    TDengine 的 interval 窗口默认从 epoch 对齐（而非 start），窗口起点 wstart
            //    与点位序列存在固定偏移，不能按 wstart 精确匹配；改用「窗口中心」归属到
            //    时间上最近的点位（偏差最多半个窗口），保证每个有数据的窗口都能归位、不丢失。
            tdTemplate.query(sql, rs -> {
                long wstart = rs.getTimestamp("wstart").getTime();
                long ts = rs.getTimestamp("time").getTime();
                BigDecimal value = rs.getBigDecimal("value");
                if (value == null) {
                    return;
                }
                long winCenter = wstart + step / 2;
                int idx = (int) Math.round((double) (winCenter - start) / step);
                if (idx < 0 || idx >= points.size()) {
                    return;
                }
                YDataAnalysisSeriesPointVO p = points.get(idx);
                // 同一点位保留该时段最后一次上报；取真实上报时刻（比窗口起点更精确）
                if (p.getValue() == null || ts > p.getTs()) {
                    p.setTs(ts);
                    p.setTime(TIME_FORMATTER.get().format(new Date(ts)));
                    p.setValue(value);
                }
            }, deviceId, start, end);
        } catch (Exception ex) {
            // 4. 查询失败（如表名/字段名不对）记录告警日志，返回空列表，图上不画曲线
            log.warn("[yGetSeries] TDengine 序列查询失败, deviceId={}, key={}, msg={}",
                    deviceId, fieldName, ex.getMessage());
            return new ArrayList<>();
        }
        return points;
    }

    // ==================== 导出专用：多设备多属性统一时间网格 ====================

    /**
     * 导出专用查询：多设备多属性 + 统一时间网格。
     *
     * <p>与 {@link #yGetSeries} 独立：/series 给图表（单设备单属性 + 抽稀 X 轴标签），
     * /export 给"导出 Excel"按钮（多设备多属性 + 完整时间网格，结构直接对 Excel/CSV 友好）。</p>
     *
     * <p>实现要点：</p>
     * <ul>
     *   <li>1. 加载模块下设备，按 deviceId 索引；调用方给的 deviceId 不在模块里时，对应列留空</li>
     *   <li>2. 按 (startTs, endTs, intervalMinutes) 生成统一时间网格——起点对齐到 step 倍数
     *       （与 TDengine INTERVAL 窗口起点一致），保证索引与 wstart 精确匹配</li>
     *   <li>3. 对每个 item 用 INTERVAL 拉一次数据，把结果归位到统一网格；
     *       多次请求独立失败互不影响，单条失败只丢对应列</li>
     * </ul>
     *
     * <p>采样点 vs 实际数据点：用户选的间隔小于设备实际数据密度时，部分窗口无数据，
     * rows 对应单元格为空字符串。这是 INTERVAL 按窗口聚合的预期行为，不做插值
     * 填充（避免引入虚假数据）。</p>
     */
    @Override
    public YDataAnalysisExportRespVO yExport(YDataAnalysisExportReqVO req) {
        YDataAnalysisExportRespVO resp = new YDataAnalysisExportRespVO();
        long start = req.getStartTs();
        long end = req.getEndTs();
        if (end <= start) {
            return resp;
        }
        int interval = req.getIntervalMinutes();
        long step = interval * 60_000L;
        // 时间网格起点：与 TDengine INTERVAL 窗口对齐（按 step 向下取整）
        long gridStart = (long) Math.floor((double) start / step) * step;
        int gridSize = (int) Math.floor((double) (end - gridStart) / step) + 1;
        if (gridSize <= 0) {
            return resp;
        }

        // 1. 加载模块下设备，按 deviceId 索引
        List<YModuleDeviceRespVO> devices = moduleService.getModuleDevices(req.getModuleId());
        if (CollUtil.isEmpty(devices)) {
            return resp;
        }
        Map<Long, YModuleDeviceRespVO> deviceMap = devices.stream()
                .filter(d -> d.getDeviceId() != null)
                .collect(Collectors.toMap(YModuleDeviceRespVO::getDeviceId, d -> d, (a, b) -> a));

        // 2. 初始化时间网格（每行 = 一个时间点 + 各列占位）
        List<List<Object>> rows = new ArrayList<>(gridSize);
        SimpleDateFormat rowSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < gridSize; i++) {
            long w = gridStart + (long) i * step;
            List<Object> row = new ArrayList<>();
            row.add(rowSdf.format(new Date(w)));
            rows.add(row);
        }

        // 3. 按 item 拉数据归位
        List<String> headers = new ArrayList<>();
        headers.add("时间");
        for (YDataAnalysisExportReqVO.Item item : req.getItems()) {
            YModuleDeviceRespVO device = deviceMap.get(item.getDeviceId());
            if (device == null) {
                // 设备不在模块里：该列全部留空
                for (List<Object> row : rows) {
                    row.add("");
                }
            } else {
                String tableName = Constants.getProductPropertySTableName(device.getDeviceType());
                String fieldName = yBuildFieldName(item.getKey());
                BigDecimal[] vals = yQuerySeriesAligned(tableName, fieldName, item.getDeviceId(),
                        gridStart, end, interval, gridSize, step);
                for (int i = 0; i < gridSize; i++) {
                    BigDecimal v = vals[i];
                    rows.get(i).add(v == null ? "" : v);
                }
            }
            // 表头：设备名-属性名（缺一就只显示另一边）
            String header = (item.getDeviceName() != null && !item.getDeviceName().isEmpty()
                    ? item.getDeviceName() + "-" : "")
                    + (item.getLabel() != null && !item.getLabel().isEmpty()
                            ? item.getLabel() : item.getKey());
            headers.add(header);
        }

        resp.setHeaders(headers);
        resp.setRows(rows);
        resp.setActualPoints(gridSize);
        resp.setTimeFormat("yyyy-MM-dd HH:mm");
        return resp;
    }

    /**
     * 导出专用：单台设备单个属性按 INTERVAL 降采样，结果按 wstart 精确归位到给定网格。
     *
     * <p>与 {@link #yQuerySeries} 差异：yQuerySeries 是"窗口中心归位到最近点位"，
     * 适合画图（容忍半窗口误差）；本方法按 wstart 精确索引，适合导出对齐——多设备多列
     * 必须按同一时间轴对齐，偏差会导致"同一时间不同设备看到的窗口不一致"。</p>
     *
     * @param gridStart 网格起点（已对齐到 step 倍数）
     * @param gridSize  网格大小
     * @param step      网格步长（毫秒）
     * @return 大小 = gridSize 的数组，索引 i 对应 gridStart + i*step，null = 窗口无数据
     */
    private BigDecimal[] yQuerySeriesAligned(String tableName, String fieldName, Long deviceId,
                                              long gridStart, long end, int interval,
                                              int gridSize, long step) {
        BigDecimal[] arr = new BigDecimal[gridSize];
        String sql = String.format(
                "select _wstart as wstart,last(%s) as `value` from %s where device_id=? and time>=? and time<=? interval(%dm)",
                fieldName, tableName, interval);
        try {
            tdTemplate.query(sql, rs -> {
                long wstart = rs.getTimestamp("wstart").getTime();
                BigDecimal value = rs.getBigDecimal("value");
                if (value == null) {
                    return;
                }
                int idx = (int) Math.round((double) (wstart - gridStart) / step);
                if (idx < 0 || idx >= gridSize) {
                    return;
                }
                arr[idx] = value;
            }, deviceId, gridStart, end);
        } catch (Exception ex) {
            log.warn("[yExport] TDengine 序列查询失败, deviceId={}, key={}, msg={}",
                    deviceId, fieldName, ex.getMessage());
        }
        return arr;
    }

}
