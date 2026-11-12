package com.enjoyiot.eiot.ruleengine.handler.sys;

import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.message.service.MessageService;
import com.enjoyiot.eiot.ruleengine.handler.DeviceMessageHandler;
import com.enjoyiot.module.eiot.api.alert.AlertApi;
import com.enjoyiot.module.eiot.api.alert.dto.Message;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import com.enjoyiot.module.eiot.api.thingmodel.ThingModelApi;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 设备阈值检查处理器 —— 替代规则引擎处理单一属性阈值告警。
 *
 * <p>不依赖 Listener/Filter/Action/Trigger 体系，直接在 Handler 中完成：
 * 阈值比对 → 参数构建 → 多渠道发送 → 告警状态追踪 → 恢复通知 → 属性联动。</p>
 *
 * <p><b>模板与联动解耦</b>：告警模板为可选配置。未绑定模板或模板构建失败时，
 * 跳过告警通知（含恢复通知），但属性联动（action）照常执行。</p>
 *
 * <h2>阈值来源</h2>
 * DeviceInfo.alertThresholds，从 y_pond.alert_thresholds JSON 解析。
 *
 * <h2>配置格式（v2）</h2>
 * <pre>
 * {
 *   "DO": {
 *     "alert": [                              // 告警规则列表
 *       {"condition":"lt", "threshold":3, "level":"严重", "desc":"溶氧低于3mg/L，触发严重告警"}
 *     ],
 *     "action": [                             // 联动规则列表（可选）
 *       {"condition":"lt", "threshold":3, "key":"ge_aerator1", "type":"propertiesSet", "value":1, "desc":"溶氧低于3mg/L，开启增氧机"},
 *       {"condition":"gt", "threshold":4, "key":"ge_aerator1", "type":"propertiesSet", "value":0, "desc":"溶氧高于4mg/L，关闭增氧机"}
 *     ]
 *   }
 * }
 * </pre>
 * <ul>
 *   <li>condition: lt / gt / le / ge / eq / neq，与上报值做比较</li>
 *   <li>alert：满足任一条件即触发告警，取第一条命中的规则构建通知参数</li>
 *   <li>action：满足条件即触发联动，目前仅支持 type=propertiesSet（属性设置）</li>
 * </ul>
 *
 * <h2>联动逻辑</h2>
 * 每次属性上报都会评估 action 条件；条件满足时读取目标属性（当前设备上 key 对应属性）的实时缓存值，
 * 若与目标 value 不一致才执行属性设置下发（等价 deviceCtrlService.setProperty(deviceId, {key: value})），
 * 下发后乐观回写缓存，避免周期上报导致重复下发。
 *
 * <h2>告警恢复</h2>
 * 值回到正常范围（所有 alert 条件均不满足）时，如果上一次处于告警状态（Redis key alerting），
 * 自动发送恢复通知并标记为 recovered。
 *
 * @author EnjoyIot
 */
@Slf4j
@Component
@Order(25) // 介于 DevicePropertyHandler(20) 和 DeviceStateCheckHandler(30) 之间
public class YDeviceThresholdCheckHandler implements DeviceMessageHandler {

    @Resource private DeviceApi deviceApi;
    @Resource private AlertApi alertApi;
    @Resource private MessageService messageService;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private ThingModelApi thingModelApi;

    /**
     * Redis 告警状态 Key 模板：eiot:alert:state:{deviceId}:{propKey}
     * <p>状态必须按「设备 + 属性」区分，同一设备不同属性（如 PH、NH）的告警/恢复状态互不干扰，
     * 否则一个属性告警会把另一个属性的正常状态覆盖成 alerting，导致通知反复发送。</p>
     */
    private static final String ALERT_STATE_KEY = "eiot:alert:state:%s:%s";

    @Override
    public void handle(ThingModelMessage msg) {
        // === 1. 过滤：只处理属性上报 ===
        if (!ThingModelMessage.TYPE_PROPERTY.equals(msg.getType())) return;
        if (!ThingModelMessage.ID_PROPERTY_REPORT.equals(msg.getIdentifier())) return;
        if (!(msg.getData() instanceof Map)) return;

        // === 2. 获取设备信息（含阈值和模板ID） 例会 ===
        DeviceInfo device = deviceApi.getDeviceInfoFromCache(msg.getDeviceId());
        if (device == null) return;
        // 塘口告警开关关闭 → 跳过
        if (device.getAlertEnabled() == null || !device.getAlertEnabled()) return;
        // 无阈值配置 → 跳过（没有阈值规则就没有告警和联动）
        Map<String, Object> thresholds = device.getAlertThresholds();
        if (thresholds == null || thresholds.isEmpty()) return;

        // === 3. 预加载模板和渠道（模板可空：没有模板则不发告警通知，联动照常执行） ===
        List<Long> templateIds = device.getAlertTemplateIds();
        List<Message> templates = new ArrayList<>();
        if (templateIds != null) {
            for (Long tid : templateIds) {
                //根据模板id 构建出message
                Message tmpl = alertApi.buildMessageFromTemplate(tid);
                if (tmpl != null) templates.add(tmpl);
            }
        }

        // === 4. 预加载物模型属性定义（标识符 → 属性），用于告警参数中的中文名与单位 ===
        Map<String, ThingModel.Property> propDefs = loadPropertyMap(device.getProductKey());

        // === 5. 遍历上报属性 ===
        Map<String, Object> data = (Map<String, Object>) msg.getData();
        //设备端发生的事件时候的时间戳
        Long occurred = msg.getOccurred() != null ? msg.getOccurred() : System.currentTimeMillis();
        //遍历上传的参数
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            //获取上报属性值
            String propKey = entry.getKey();
            // 物模型白名单：未在物模型中定义的属性直接忽略，跳过告警与联动
            // （防止 alert_thresholds 配置 key 拼错时仍触发处理、污染缓存或写入无效告警）
            if (propDefs == null || !propDefs.containsKey(propKey)) {
                log.debug("联动跳过：上报属性 {} 不在物模型白名单", propKey);
                continue;
            }
            //根据属性值获取当前属性配置的规则
            Object cfg = thresholds.get(propKey);
            if (!(cfg instanceof Map)) continue;

            //这个注解是防止编译的时候发出告警
            //告警原因：cfg 是 object 类型，所以转换成 map 类型的时候，无法判断当前
            //类型是否匹配
            @SuppressWarnings("unchecked")
            Map<String, Object> rule = (Map<String, Object>) cfg;
            Double val = toDouble(entry.getValue());
            if (val == null) continue;

            // === 6. 告警比对与通知（有模板才发送，无模板仅跳过通知） ===
            doAlertCheck(device, propKey, val, rule, occurred, templates, propDefs);

            // === 7. 联动执行：无论是否触发告警、有无模板，满足条件即下发 ===
            doLinkage(device, rule, val);
        }
    }

    // ========== 告警比对 ==========


    /**
     * 执行告警比对与通知。
     *
     * <p>处理步骤：</p>
     * <ol>
     *   <li>没有绑定告警模板 → 不发通知，直接结束</li>
     *   <li>比对阈值，判断本次是「告警」还是「正常（恢复）」</li>
     *   <li>读取上一次记录的告警状态</li>
     *   <li>状态没有变化 → 不重复发送</li>
     *   <li>构建模板参数，按模板列表逐个渠道发送</li>
     *   <li>记录本次状态，供下一轮判断</li>
     * </ol>
     *
     * @param device    设备信息
     * @param propKey   上报属性标识
     * @param val       上报值
     * @param rule      阈值规则 Map（含 alert/action 列表），从 alertThresholds JSON 解析
     * @param occurred  设备端事件发生时间戳
     * @param templates 告警模板列表（可空：为空则不发告警/恢复通知）
     * @param propDefs  物模型属性定义（标识符 → 属性），用于中文名/单位填充
     */
    private void doAlertCheck(DeviceInfo device, String propKey, Double val, Map<String, Object> rule, Long occurred, List<Message> templates, Map<String, ThingModel.Property> propDefs) {

        // 步骤1：没有绑定告警模板 → 不发任何通知
        if (templates.isEmpty()) {
            return;
        }

        // 步骤2：比对阈值。alert 非空表示命中规则（告警），为空表示已回到正常范围
        Map<String, Object> alert = evaluate(rule, val);
        boolean isAlarm = alert != null;

        // 步骤3：读取上一次的告警状态，key 形如 eiot:alert:state:{deviceId}:{propKey}
        String stateKey = String.format(ALERT_STATE_KEY, device.getId(), propKey);
        String lastState = stringRedisTemplate.opsForValue().get(stateKey);
        boolean wasAlerting = "alerting".equals(lastState);

        // 步骤4：状态没有变化 → 不重复发送（设备按周期上报，不做这步会一直刷消息）
        if (isAlarm && wasAlerting) {
            // 上一轮已在告警中，本次仍是告警，之前已经通知过
            return;
        }
        if (!isAlarm && !wasAlerting) {
            // 上一轮正常，本次也正常，没有告警需要恢复
            return;
        }

        // 步骤5：构建模板参数。告警用命中的规则，恢复没有命中规则传空
        String nextState;
        Map<String, Object> param;
        if (isAlarm) {
            nextState = "alerting";
            param = buildParam(device, propKey, val, alert, occurred, propDefs);
        } else {
            nextState = "recovered";
            param = buildParam(device, propKey, val, new HashMap<>(), occurred, propDefs);
        }

        // 步骤6：按模板列表逐个渠道发送（钉钉/短信/语音等）
        for (Message tmpl : templates) {
            Message msg = Message.builder()
                    .content(tmpl.getContent())
                    .channelCode(tmpl.getChannelCode())
                    .channelConfig(tmpl.getChannelConfig())
                    .templateCode(tmpl.getTemplateCode())
                    .tenantId(device.getOwnerTenantId())
                    .build();
            msg.setParam(new HashMap<>(param));
            messageService.sendMessage(msg);
        }

        // 步骤7：记录本次状态，供下一轮判断
        stringRedisTemplate.opsForValue().set(stateKey, nextState);
    }

    /**
     * 遍历配置中的 alert 规则列表，返回第一条命中的规则。
     * <p>alert 配置格式：{"condition":"lt|gt|le|ge|eq|neq","threshold":3,"level":"严重","desc":"..."}</p>
     * 任一条规则命中即视为告警；全部未命中返回 null（正常范围）。
     *
     * @param rule 阈值规则 Map（含 alert/action 列表），从 alertThresholds JSON 解析
     * @param val  设备上报的当前值
     * @return 命中的 alert 规则，未触发返回 null
     */
    private Map<String, Object> evaluate(Map<String, Object> rule, Double val) {
        Object alertObj = rule.get("alert");
        if (!(alertObj instanceof List)) return null;
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) alertObj;
        for (Map<String, Object> alert : alerts) {
            String condition = (String) alert.get("condition");
            Double threshold = toDouble(alert.get("threshold"));
            if (condition == null || threshold == null) continue;
            if (evalOperator(condition, val, threshold)) {
                return alert;
            }
        }
        return null;
    }

    private boolean evalOperator(String op, Double val, Double target) {
        return switch (op) {
            case "eq"  -> Math.abs(val - target) < 0.0001;
            case "neq" -> Math.abs(val - target) >= 0.0001;
            case "gt"  -> val > target;
            case "gte" -> val >= target;
            case "lt"  -> val < target;
            case "lte" -> val <= target;
            default    -> false;
        };
    }

    // ========== 参数构建 ==========

    private Map<String, Object> buildParam(DeviceInfo d, String propKey, Double val,
            Map<String, Object> alert, Long time, Map<String, ThingModel.Property> propDefs) {
        String condition = (String) alert.getOrDefault("condition", "");
        Double threshold = toDouble(alert.get("threshold"));
        String level = (String) alert.getOrDefault("level", "");
        String desc = (String) alert.getOrDefault("desc", "");
        boolean isLow = "lt".equals(condition) || "lte".equals(condition);
        boolean isHigh = "gt".equals(condition) || "gte".equals(condition);
        // 兼容旧模板：alertType 描述触发方向
        String alertType;
        if (condition.isEmpty()) {
            alertType = "已恢复正常";
        } else if (isLow) {
            alertType = "低于阈值";
        } else if (isHigh) {
            alertType = "高于阈值";
        } else {
            alertType = "触发告警";
        }
        // 属性名称与单位：propName 展示为「标识符（中文名）」，如 DO（溶氧）；
        // 物模型未配置 name/unit 时回退为标识符/空
        String propName = propKey;
        String unit = "";
        if (propDefs != null) {
            ThingModel.Property prop = propDefs.get(propKey);
            if (prop != null) {
                if (prop.getName() != null && !prop.getName().isEmpty()) {
                    propName = propKey + "（" + prop.getName() + "）";
                }
                if (prop.getUnit() != null) {
                    unit = prop.getUnit();
                }
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("deviceId",   d.getId());
        p.put("farmId",     d.getFarmId());
        p.put("pondId",     d.getPondId());
        p.put("moduleId",   d.getModuleId());
        p.put("farmName",   d.getFarmName() != null ? d.getFarmName() : "");
        p.put("pondName",   d.getPondName() != null ? d.getPondName() : "");
        p.put("propName",   propName);
        p.put("propKey",    propKey);
        p.put("value",      val);
        p.put("min",        isLow ? threshold : "");
        p.put("max",        isHigh ? threshold : "");
        p.put("condition",  condition);
        p.put("threshold",  threshold);
        p.put("desc",       desc);
        p.put("unit",       unit);
        p.put("level",      level);
        p.put("alertType",  alertType);
        p.put("deviceName", d.getDn());
        p.put("time",       new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(time)));
        return p;
    }

    // ========== 联动执行 ==========

    /**
     * 执行属性联动。遍历 action 规则列表，满足条件的下发属性设置指令。
     *
     * <p>action 配置：{"condition":"lt|gt...","threshold":3,"key":"ge_aerator1",
     * "type":"propertiesSet","value":1}</p>
     *
     * <p>触发逻辑：条件满足且目标属性当前缓存值不等于目标值时，下发属性设置。
     * 设备会立即上报当前状态，缓存随后自动同步，不需要手动回写缓存。</p>
     *
     * @param device 当前上报设备（联动目标即该设备上的控制属性，如增氧机开关）
     * @param rule   阈值规则 Map（含 alert/action 列表）
     * @param val    设备上报的当前值
     */
    private void doLinkage(DeviceInfo device, Map<String, Object> rule, Double val) {
        // 步骤1：取出 action 配置，没有配置则跳过
        Object actionObj = rule.get("action");
        if (!(actionObj instanceof List)) {
            return;
        }

        // 步骤2：遍历每条联动规则
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) actionObj;
        for (Map<String, Object> action : actions) {
            // 步骤2.1：提取判断条件与阈值，任一缺失则跳过
            String condition = (String) action.get("condition");
            Double threshold = toDouble(action.get("threshold"));
            if (condition == null || threshold == null) {
                continue;
            }
            // 步骤2.2：条件不满足 → 不需要联动
            if (!evalOperator(condition, val, threshold)) {
                continue;
            }
            // 步骤2.3：目前只支持 propertiesSet 类型
            if (!"propertiesSet".equals(action.get("type"))) {
                continue;
            }
            // 步骤2.4：必填参数缺失则跳过
            String key = (String) action.get("key");
            Object value = action.get("value");
            if (key == null || key.isEmpty() || value == null) {
                continue;
            }
            // 步骤2.5：读取目标属性的当前缓存值
            Map<String, DevicePropertyCache> props = deviceApi.getPropertiesFromCache(device.getId());
            DevicePropertyCache cache = (props == null) ? null : props.get(key);
            Object curValue = (cache == null) ? null : cache.getValue();
            // 步骤2.6：当前值已经等于目标值 → 跳过，避免重复下发
            if (valueEquals(curValue, value)) {
                log.info("联动跳过：设备 {} 属性 {} 当前值已等于目标值 {}", device.getId(), key, value);
                continue;
            }
            // 步骤2.7：下发属性设置指令
            deviceApi.setProperty(device.getId(), Map.of(key, value));
        }
    }

    /** 值比较（类型归一化）：boolean 与 1/0、数字与数字字符串视为等价 */
    private boolean valueEquals(Object cur, Object target) {
        Double c = toNormalizedDouble(cur);
        Double t = toNormalizedDouble(target);
        if (c != null && t != null) {
            return Math.abs(c - t) < 0.0001;
        }
        return String.valueOf(cur).equals(String.valueOf(target));
    }

    private Double toNormalizedDouble(Object v) {
        if (v instanceof Boolean) return ((Boolean) v) ? 1.0 : 0.0;
        return toDouble(v);
    }

    // ========== 工具方法 ==========

    /**
     * 加载物模型属性定义（标识符 → 属性），用于告警参数中的中文名（propName）与单位（unit）。
     * 物模型不存在或未配置时返回空 Map，告警参数回退为属性标识符/空字符串。
     *
     * @param productKey 产品 Key
     */
    private Map<String, ThingModel.Property> loadPropertyMap(String productKey) {
        if (productKey == null || productKey.isEmpty()) {
            return Collections.emptyMap();
        }
        ThingModel tm = thingModelApi.getThingModelByProductKeyFromCache(productKey);
        if (tm == null || tm.getModel() == null || tm.getModel().getProperties() == null) {
            return Collections.emptyMap();
        }
        Map<String, ThingModel.Property> map = new HashMap<>();
        for (ThingModel.Property prop : tm.getModel().getProperties()) {
            if (prop != null && prop.getIdentifier() != null) {
                map.put(prop.getIdentifier(), prop);
            }
        }
        return map;
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
