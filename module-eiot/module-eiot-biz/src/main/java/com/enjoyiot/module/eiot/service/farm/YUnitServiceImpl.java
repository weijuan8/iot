package com.enjoyiot.module.eiot.service.farm;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel.Property;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO.YUnitAlert;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO.YUnitDevice;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO.YUnitDevice.ControlItem;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO.YUnitDevice.SensorItem;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO.YUnitStats;
import com.enjoyiot.module.eiot.dal.dataobject.AlertRecordDO;
import com.enjoyiot.module.eiot.dal.dataobject.deviceinfo.EiotDeviceInfoDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDeviceDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YPondDO;
import com.enjoyiot.module.eiot.dal.dataobject.product.ProductDO;
import com.enjoyiot.module.eiot.dal.mysql.AlertRecordMapper;
import com.enjoyiot.module.eiot.dal.mysql.deviceinfo.EiotDeviceInfoMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YModuleDeviceMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YModuleMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YPondMapper;
import com.enjoyiot.module.eiot.dal.mysql.product.ProductMapper;
import com.enjoyiot.module.eiot.dal.redis.no.EiotRedisDAO;
import com.enjoyiot.module.eiot.service.product.ThingModelService;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 单元(模块)聚合 Service 实现
 *
 * @author EnjoyIot
 */
@Slf4j
@Service
public class YUnitServiceImpl implements YUnitService {

    @Resource
    private YModuleMapper yModuleMapper;

    @Resource
    private YPondMapper yPondMapper;

    @Resource
    private YModuleDeviceMapper yModuleDeviceMapper;

    @Resource
    private EiotDeviceInfoMapper eiotDeviceInfoMapper;

    @Resource
    private ProductMapper productMapper;

    @Resource
    private ThingModelService thingModelService;

    @Resource
    private EiotRedisDAO eiotRedisDAO;

    @Resource
    private AlertRecordMapper alertRecordMapper;

    /** 告警最大返回条数 */
    private static final int ALERT_LIMIT = 10;

    /** 设备在线状态码 */
    private static final int DEVICE_STATE_ONLINE = 1;

    @Override
    @TenantIgnore
    // 绕过自动租户过滤，模块已通过塘口层级做权限控制
    /***
     * 关于控制开关
     * 1 目前制作了布尔类型的参数的适配 service 没有适配开关。并且参数只能是布尔类型
     * **/
    public YUnitDetailVO getUnitDetail(Long moduleId) {
        // 1. 校验模块存在
        YModuleDO module = yModuleMapper.selectById(moduleId);
        if (ObjectUtil.isNull(module)) {
            throw exception(ErrorCodeConstants.MODULE_NOT_EXISTS);
        }

        // 2. 获取模块绑定的设备ID
        //    2.1 关联表字段多（farm_id/pond_id 等都是冗余字段），select() 限定只查 device_id 一列
        //    2.2 selectObjs 直接返回该列的值，不会再封装成 YModuleDeviceDO 对象
        //    2.3 device_id 上有唯一索引，一个设备只属于一个模块，所以不用去重
        List<Long> deviceIds = yModuleDeviceMapper.selectObjs(
                new LambdaQueryWrapper<YModuleDeviceDO>()
                        .select(YModuleDeviceDO::getDeviceId)
                        .eq(YModuleDeviceDO::getModuleId, moduleId));

        // 3. 获取设备信息
        List<EiotDeviceInfoDO> deviceDOs = CollUtil.isEmpty(deviceIds)
                ? Collections.emptyList()
                : eiotDeviceInfoMapper.selectBatchIds(deviceIds);

        // 4. 构建 deviceId → deviceName 映射（供告警组装用）
        Map<Long, String> deviceNameMap = deviceDOs.stream()
                .collect(Collectors.toMap(EiotDeviceInfoDO::getId, EiotDeviceInfoDO::getName, (a, b) -> a));

        // 5. 获取产品名称映射（productKey 去重后一次性批量查，不要循环单条查库）
        Set<String> productKeys = deviceDOs.stream()
                .map(EiotDeviceInfoDO::getProductKey)
                .filter(ObjectUtil::isNotNull)
                .collect(Collectors.toSet());
        Map<String, String> productNameMap = new LinkedHashMap<>();
        for (ProductDO product : productMapper.selectList(ProductDO::getProductKey, productKeys)) {
            productNameMap.put(product.getProductKey(), product.getName());
        }

        // 6. 获取物模型（productKeys 已去重）
        Map<String, ThingModel> thingModelMap = new LinkedHashMap<>();
        for (String pk : productKeys) {
            try {
                ThingModel tm = thingModelService.getThingModelByProductKeyFromCache(pk);
                if (tm != null) {
                    thingModelMap.put(pk, tm);
                }
            } catch (Exception e) {
                log.warn("[getUnitDetail] 获取物模型失败, productKey={}", pk, e);
            }
        }

        // 7. 获取每个设备的实时属性值
        Map<Long, Map<String, DevicePropertyCache>> devicePropertiesMap = new LinkedHashMap<>();
        for (EiotDeviceInfoDO device : deviceDOs) {
            try {
                Map<String, DevicePropertyCache> props = eiotRedisDAO.getProperties(device.getId());
                devicePropertiesMap.put(device.getId(), props != null ? props : Collections.emptyMap());
            } catch (Exception e) {
                log.warn("[getUnitDetail] 获取设备实时属性失败, deviceId={}", device.getId(), e);
                devicePropertiesMap.put(device.getId(), Collections.emptyMap());
            }
        }

        // 8. 查询塘口（用于解析告警阈值），养殖场由 service/前端需要时单独获取
        YPondDO pond = module.getPondId() == null ? null : yPondMapper.selectById(module.getPondId());

        // 9. 解析塘口告警阈值（key 转小写，便于忽略大小写匹配物模型属性标识符）
        Map<String, Object> pondThresholds = parsePondThresholds(pond);

        // 10. 构建设备列表（每台设备下同时含传感器参数与控制项）
        List<YUnitDevice> unitDevices = buildDevices(deviceDOs, thingModelMap, devicePropertiesMap, productNameMap, pondThresholds);

        // 11. 获取告警记录
        List<YUnitAlert> alerts = buildAlerts(moduleId, deviceNameMap,
                pond != null ? pond.getPondName() : null, module.getModuleName());

        // 12. 计算统计概览
        YUnitStats stats = buildStats(unitDevices, alerts);

        return YUnitDetailVO.builder()
                .stats(stats)
                .devices(unitDevices)
                .alerts(alerts)
                .build();
    }

    /**
     * 构建设备列表（单次遍历：每台设备同时组装传感器参数与控制项）
     * - 控制项：accessMode=rw 且数据类型为 bool 的属性参数（作为开关控制）
     * - 传感器项：其余物模型 properties + Redis 实时值 + 塘口告警阈值
     */
    private List<YUnitDevice> buildDevices(List<EiotDeviceInfoDO> deviceDOs,
                                           Map<String, ThingModel> thingModelMap,
                                           Map<Long, Map<String, DevicePropertyCache>> devicePropertiesMap,
                                           Map<String, String> productNameMap,
                                           Map<String, Object> pondThresholds) {
        List<YUnitDevice> result = new ArrayList<>();
        for (EiotDeviceInfoDO device : deviceDOs) {
            String pk = device.getProductKey();
            if (pk == null || !thingModelMap.containsKey(pk)) {
                continue;
            }
            ThingModel tm = thingModelMap.get(pk);
            ThingModel.Model model = tm.getModel();
            if (model == null || CollUtil.isEmpty(model.getProperties())) {
                continue;
            }

            Map<String, DevicePropertyCache> props = devicePropertiesMap.getOrDefault(
                    device.getId(), Collections.emptyMap());

            List<ControlItem> controlItems = new ArrayList<>();
            List<SensorItem> sensorItems = new ArrayList<>();

            for (Property prop : model.getProperties()) {
                boolean isBoolSwitch = "rw".equals(prop.getAccessMode())
                        && prop.getDataType() != null
                        && "bool".equals(prop.getDataType().getType());

                if (isBoolSwitch) {
                    // 布尔读写参数 → 控制项
                    DevicePropertyCache cache = props.get(prop.getIdentifier());
                    controlItems.add(ControlItem.builder()
                            .identifier(prop.getIdentifier())
                            .name(prop.getName())
                            .value(cache != null ? cache.getValue() : null)
                            .description(prop.getDescription())
                            .build());
                } else {
                    // 其余属性 → 传感器项
                    DevicePropertyCache cache = props.get(prop.getIdentifier());
                    Object min = null;
                    Object max = null;
                    if (prop.getDataType() != null && prop.getDataType().getSpecs() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> specMap = (Map<String, Object>) prop.getDataType().getSpecs();
                        min = specMap.get("min");
                        max = specMap.get("max");
                    }
                    Object threshold = normalizeThreshold(findThreshold(prop.getIdentifier(), pondThresholds));
                    Object currentValue = cache != null ? cache.getValue() : null;
                    String status = calcSensorStatus(currentValue, threshold, min, max);
                    String thresholdText = buildThresholdText(threshold, prop.getUnit());
                    sensorItems.add(SensorItem.builder()
                            .identifier(prop.getIdentifier())
                            .name(prop.getName())
                            .value(currentValue)
                            .unit(prop.getUnit())
                            .dataType(prop.getDataType() != null ? prop.getDataType().getType() : null)
                            .accessMode(prop.getAccessMode())
                            .min(min)
                            .max(max)
                            .occurred(cache != null ? cache.getOccurred() : null)
                            .threshold(threshold)
                            .status(status)
                            .thresholdText(thresholdText)
                            .build());
                }
            }

            // 仅在 sensors 或 controls 至少有一项非空时输出该设备
            if (CollUtil.isNotEmpty(sensorItems) || CollUtil.isNotEmpty(controlItems)) {
                result.add(YUnitDevice.builder()
                        .deviceId(device.getId())
                        .deviceName(device.getName())
                        .productKey(pk)
                        .productName(productNameMap.get(pk))
                        .online(Objects.equals(device.getState(), DEVICE_STATE_ONLINE))
                        .sensors(sensorItems)
                        .controls(controlItems)
                        .build());
            }
        }
        return result;
    }

    /**
     * 构建告警记录列表
     * 按 moduleId 筛选，最近 N 条
     */
    private List<YUnitAlert> buildAlerts(Long moduleId,
                                          Map<Long, String> deviceNameMap,
                                          String pondName,
                                          String moduleName) {
        List<AlertRecordDO> records = alertRecordMapper.selectList(
                new LambdaQueryWrapper<AlertRecordDO>()
                        .eq(AlertRecordDO::getModuleId, moduleId)
                        .orderByDesc(AlertRecordDO::getAlertTime)
                        .last("LIMIT " + ALERT_LIMIT)
        );
        if (CollUtil.isEmpty(records)) {
            return Collections.emptyList();
        }
        return records.stream().map(r -> YUnitAlert.builder()
                .id(r.getId())
                .name(r.getName())
                .level(r.getLevel())
                .details(r.getDetails())
                .deviceName(deviceNameMap.get(r.getDeviceId()))
                .pondName(pondName)
                .moduleName(moduleName)
                .alertTime(r.getAlertTime())
                .readFlg(r.getReadFlg())
                // 阈值告警明细：当前值/预警值/比较符/单位/属性/告警类型（后端算好，前端直接展示）
                .value(r.getValue())
                .threshold(r.getThreshold())
                .cond(r.getCond())
                .unit(r.getUnit())
                .propKey(r.getPropKey())
                .propName(r.getPropName())
                .alertType(r.getAlertType())
                .build()
        ).collect(Collectors.toList());
    }

    /**
     * 构建统计概览
     */
    private YUnitStats buildStats(List<YUnitDevice> devices,
                                   List<YUnitAlert> alerts) {
        // 传感器总数 / 控制总数
        int sensorCount = 0;
        int controlTotal = 0;
        int controlOnCount = 0;
        if (CollUtil.isNotEmpty(devices)) {
            for (YUnitDevice dev : devices) {
                if (dev.getSensors() != null) sensorCount += dev.getSensors().size();
                if (CollUtil.isNotEmpty(dev.getControls())) {
                    controlTotal += dev.getControls().size();
                    for (ControlItem item : dev.getControls()) {
                        if (isOnStatus(item.getValue())) {
                            controlOnCount++;
                        }
                    }
                }
            }
        }

        return YUnitStats.builder()
                .sensorCount(sensorCount)
                .alarmCount(alerts.size())
                .controlTotal(controlTotal)
                .controlOnCount(controlOnCount)
                .build();
    }

    /**
     * 判断控制项是否处于开启状态
     */
    private boolean isOnStatus(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        String str = value.toString().trim().toLowerCase();
        return "1".equals(str) || "true".equals(str) || "on".equals(str);
    }

    /**
     * 解析塘口的告警阈值 JSON，key 统一转小写方便忽略大小写匹配。
     * 返回格式：{"nh": {"min":0, "max":0.5, "name":"氨氮", "unit":"mg/L", "level":"紧急"}, ...}
     */
    private Map<String, Object> parsePondThresholds(YPondDO pond) {
        if (pond == null || ObjectUtil.isEmpty(pond.getAlertThresholds())) {
            return Collections.emptyMap();
        }
        try {
            JSONObject json = JSONUtil.parseObj(pond.getAlertThresholds());
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (String key : json.keySet()) {
                normalized.put(key.toLowerCase(), json.get(key));
            }
            return normalized;
        } catch (Exception e) {
            log.warn("[parsePondThresholds] 解析塘口告警阈值失败, pondId={}", pond.getId(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 从塘口阈值 Map 中查找匹配指定标识符的阈值配置（忽略大小写）。
     */
    private Object findThreshold(String identifier, Map<String, Object> pondThresholds) {
        if (identifier == null || CollUtil.isEmpty(pondThresholds)) {
            return null;
        }
        return pondThresholds.get(identifier.toLowerCase());
    }

    /**
     * 兼容新旧阈值格式：
     * 新格式 {"alert":[{"condition":"lt","threshold":3,"level":"严重"},...],"action":[...]}
     * 聚合附加 min/max/level 兼容字段（min=所有低于类条件的最大阈值，max=所有高于类条件的最小阈值），
     * 便于仍按 min/max 消费的旧版前端继续展示；旧格式原样返回。
     */
    private Object normalizeThreshold(Object threshold) {
        if (!(threshold instanceof Map)) {
            return threshold;
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) threshold);
        if (!(map.get("alert") instanceof List) || ((List<?>) map.get("alert")).isEmpty()) {
            return map;
        }
        // 已显式配置 min/max 则不再聚合
        if (map.containsKey("min") || map.containsKey("max")) {
            return map;
        }
        Double min = null;
        Double max = null;
        String level = null;
        for (Object o : (List<?>) map.get("alert")) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> alert = (Map<String, Object>) o;
            String condition = alert.get("condition") != null ? String.valueOf(alert.get("condition")) : null;
            Double t = toThresholdNumber(alert.get("threshold"));
            if (condition == null || t == null) {
                continue;
            }
            if (level == null && alert.get("level") != null) {
                level = String.valueOf(alert.get("level"));
            }
            if ("lt".equals(condition) || "le".equals(condition) || "lte".equals(condition)) {
                if (min == null || t > min) {
                    min = t;
                }
            } else if ("gt".equals(condition) || "ge".equals(condition) || "gte".equals(condition)) {
                if (max == null || t < max) {
                    max = t;
                }
            }
        }
        if (min != null) {
            map.put("min", min);
        }
        if (max != null) {
            map.put("max", max);
        }
        if (level != null && !map.containsKey("level")) {
            map.put("level", level);
        }
        return map;
    }

    private Double toThresholdNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 计算传感器展示状态（normal/danger）。
     * 优先按塘口告警阈值判断；未配置阈值或值非法时回退到物模型 min/max 约束。
     * 兼容新格式 alert[] 与旧格式 min/max。
     */
    private String calcSensorStatus(Object value, Object threshold, Object modelMin, Object modelMax) {
        Double val = toThresholdNumber(value);
        if (val == null) {
            return "normal";
        }
        // 1. 塘口告警阈值判断（新格式 alert[] / 旧格式 min/max）
        if (threshold instanceof Map) {
            Map<String, Object> t = (Map<String, Object>) threshold;
            // 新格式 alert[]
            if (t.get("alert") instanceof List) {
                for (Object o : (List<?>) t.get("alert")) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> alert = (Map<String, Object>) o;
                    Double th = toThresholdNumber(alert.get("threshold"));
                    String condition = alert.get("condition") != null ? String.valueOf(alert.get("condition")) : null;
                    if (th != null && condition != null && evalOperator(condition, val, th)) {
                        return "danger";
                    }
                }
            }
            // 旧格式 min/max（normalizeThreshold 聚合出的 min/max 也走这里）
            Double min = toThresholdNumber(t.get("min"));
            Double max = toThresholdNumber(t.get("max"));
            if (min != null && val < min) {
                return "danger";
            }
            if (max != null && val > max) {
                return "danger";
            }
        }
        // 2. 回退到物模型规格 min/max
        Double mMin = toThresholdNumber(modelMin);
        Double mMax = toThresholdNumber(modelMax);
        if (mMin != null && mMax != null && (val < mMin || val > mMax)) {
            return "danger";
        }
        return "normal";
    }

    /**
     * 生成阈值展示文本，如 "严重: 低于3mg/L 高于4mg/L"。
     * 新格式 alert[] 按等级分组拼接；旧格式 min/max 回退为 "等级: 3~4单位"。
     */
    private String buildThresholdText(Object threshold, String unit) {
        if (!(threshold instanceof Map)) {
            return "";
        }
        Map<String, Object> t = (Map<String, Object>) threshold;
        String u = unit == null ? "" : unit;
        // 新格式 alert[]
        if (t.get("alert") instanceof List) {
            List<?> alerts = (List<?>) t.get("alert");
            if (alerts.isEmpty()) {
                return "";
            }
            // 按等级分组，保持原始顺序
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (Object o : alerts) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> a = (Map<String, Object>) o;
                String condition = a.get("condition") != null ? String.valueOf(a.get("condition")) : null;
                Double th = toThresholdNumber(a.get("threshold"));
                if (condition == null || th == null) {
                    continue;
                }
                String level = a.get("level") != null ? String.valueOf(a.get("level")) : "告警";
                grouped.computeIfAbsent(level, k -> new ArrayList<>())
                        .add(directionDesc(condition) + stripZero(th) + u);
            }
            if (grouped.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(e.getKey()).append(": ").append(String.join(" ", e.getValue()));
            }
            return sb.toString();
        }
        // 旧格式 min/max
        Double min = toThresholdNumber(t.get("min"));
        Double max = toThresholdNumber(t.get("max"));
        String level = t.get("level") != null ? String.valueOf(t.get("level")) : "告警";
        if (min != null && max != null) {
            return level + ": " + stripZero(min) + "~" + stripZero(max) + u;
        } else if (min != null) {
            return level + ": ≥ " + stripZero(min) + u;
        } else if (max != null) {
            return level + ": ≤ " + stripZero(max) + u;
        }
        return level;
    }

    /**
     * 阈值条件方向描述（与 handler 的 evalOperator 一致）。
     */
    private String directionDesc(String condition) {
        switch (condition) {
            case "lt": return "低于";
            case "le": case "lte": return "不超过";
            case "gt": return "高于";
            case "ge": case "gte": return "不低于";
            default: return "";
        }
    }

    /**
     * 条件求值：lt/le/gt/ge/eq/neq。
     */
    private boolean evalOperator(String condition, double val, double threshold) {
        switch (condition) {
            case "lt": return val < threshold;
            case "le": case "lte": return val <= threshold;
            case "gt": return val > threshold;
            case "ge": case "gte": return val >= threshold;
            case "eq": return Math.abs(val - threshold) < 0.0001;
            case "neq": return Math.abs(val - threshold) >= 0.0001;
            default: return false;
        }
    }

    /**
     * 数字展示去尾零，如 3.0 → "3"。
     */
    private String stripZero(Double value) {
        if (value == null) {
            return "";
        }
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }
}
