package com.enjoyiot.eiot.component.ge100d.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Dict;
import com.enjoyiot.eiot.component.core.ComponentServices;
import com.enjoyiot.eiot.component.core.ThingComponent;
import com.enjoyiot.eiot.component.core.model.down.*;
import com.enjoyiot.eiot.component.core.model.up.PropertyReport;
import com.enjoyiot.eiot.component.ge100d.constant.Ge100dConstants;
import com.enjoyiot.eiot.component.ge100d.model.Ge100dConfig;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>GE100D 透传组件</h1>
 *
 * <h2>架构</h2>
 * GE100D 硬件自行轮询 Modbus 从机并解析为十进制数据，以 JSON 格式上传。
 * 网关作为单一逻辑设备，服务端不感知子设备，不做 Modbus 解析。
 *
 * <h2>上行</h2>
 * <pre>
 * MQTT: ge100d/{pk}/{dn}/serial/up
 * payload: {"温度":25.5,"湿度":60.2,"ph":7.0}
 *   → handleSerialRead(pk, dn, data)
 *     → findGateway(pk, dn) → 不存在则告警丢弃
 *       → report(PropertyReport) → 规则引擎处理
 * </pre>
 *
 * <h2>下行</h2>
 * <pre>
 * serviceInvoke 固定字符串控制:
 *   → findGateway(pk, dn)
 *     → verticle.publishCommand(pk, dn, cmd) → MQTT → GE100D
 * </pre>
 */
@Slf4j
@Component
public class Ge100dComponent extends ThingComponent {

    private final Ge100dVerticle verticle;
    private Ge100dConfig config;

    /** 网关缓存: Key=dn, Value=DeviceInfo */
    private final Map<String, DeviceInfo> gatewayCache = new ConcurrentHashMap<>();

    // ========================================================================
    // 主动轮询开关状态
    // ========================================================================

    public Ge100dComponent(Ge100dVerticle verticle, ComponentServices componentServices) {
        super(componentServices);
        this.verticle = verticle;
    }

    @Override public String getType() { return Ge100dConstants.TYPE; }
    @Override public String getName() { return Ge100dConstants.NAME; }

    @Override
    public boolean stateChange(boolean enable, String cfg) {
        verticle.setGe100dComponent(this);
        if (!enable) {
            // 组件停用：停止 MQTT 服务 + 清空缓存
            verticle.stopServer();
            gatewayCache.clear();
            return true;
        }
        if (cfg == null) return false;
        config = JsonUtils.parseObject(cfg, Ge100dConfig.class);
        if (config == null) {
            log.error("ge100d config parse failed");
            return false;
        }
        verticle.stopServer();
        verticle.startServer(config);
        gatewayCache.clear();
        return true;
    }

    // ========================================================================
    // 通用网关查找
    // ========================================================================

    /**
     * 从本地缓存或 DB 查找网关设备，查到后放入缓存。
     *
     * @return 网关设备信息，未找到或字段不完整返回 null
     */
    private DeviceInfo lookupGateway(String pk, String dn) {
        DeviceInfo gw = gatewayCache.get(dn);
        if (gw == null) {
            gw = componentServices.getDeviceApi().getDeviceByPkDnByCache(pk, dn);
            if (gw == null) {
                log.warn("ge100d unknown gateway: pk={}, dn={}", pk, dn);
                return null;
            }
            gatewayCache.put(dn, gw);
            log.info("ge100d gateway found: pk={}, dn={}, id={}", pk, dn, gw.getId());
        }
        if (gw.getProductKey() == null || gw.getDn() == null) {
            log.warn("ge100d gateway incomplete: pk={}, dn={}, id={}, productKey={}",
                    pk, dn, gw.getId(), gw.getProductKey());
            return null;
        }
        return gw;
    }

    // ========================================================================
    // 上行
    // ========================================================================

    /**
     * 处理网关上报的已解析 JSON 数据。
     *
     * @param pk   产品 key（从 topic 提取）
     * @param dn   网关 dn（从 topic 提取）
     * @param data 传感器键值对，例: {"温度":25.5,"湿度":60.2}
     */
    public void handleSerialRead(String pk, String dn, Map<String, Object> data) {
        DeviceInfo gw = lookupGateway(pk, dn);
        if (gw == null || data.isEmpty()) return;

        Dict params = Dict.create();
        data.forEach((k, v) -> params.set(k, v));
        report(PropertyReport.builder()
                .productKey(gw.getProductKey())
                .deviceName(gw.getDn())
                .params(params)
                .time(System.currentTimeMillis())
                .build());
    }


    @Override
    protected void serviceInvoke(ServiceInvoke action) {
    }


    @Override protected void propertySet(PropertySet action) {
        log.debug("ge100d propertySet not supported in transparent mode");
        DeviceInfo gw = lookupGateway(action.getProductKey(), action.getDeviceName());
        if (gw == null) return;

        // 2. 遍历所有服务参数，目前只适配布尔值 开关
        for (var e : action.getParams().entrySet()) {
            //==遍历map 获取key 和value
            String k = e.getKey();
            String v = e.getValue() != null ? e.getValue().toString() : ""; // 参数 value，如 "RELAY1_ON"

            //===判断key 也就是参数名称（参数标识符）,做二次判断 如果参数不是ge_  开头的说明不是ge100d 网关的参数，说明哪里出错了
            if (k.startsWith(Ge100dConstants.PROPERTY_PREFIX)) {
                // 通过 MQTT 下发命令字符串给 GE100D 硬件
                // → Ge100dVerticle.publishCommand()
                //   → MQTT topic: ge100d/{pk}/{dn}/serial/down
                //   → payload: {"cmd":"RELAY1_ON"}
                //判断是开启指令还是关闭指令
                String cmd ="";
                if(v.equals("1")){
                    //开启指令
                    cmd = Ge100dConstants.SET_ON_CMD;
                }else{
                    //关闭指令
                    cmd = Ge100dConstants.SET_OFF_CMD;
                }
                verticle.publishCommand(gw.getProductKey(), gw.getDn(), cmd);
            } else {
                // 非 raw_ 前缀的参数 → GE100D 不支持，记录日志
                log.error("接收到了错误的参数，参数不是以ge_ 开头", action.getParams());
            }
        }




    }
    @Override protected void propertyGet(PropertyGet action) {}
    @Override protected void deviceConfig(DeviceConfig action) {}
    @Override protected void deviceOta(DeviceOta action) {}
    @Override protected void deviceTopoChange(DeviceTopoChange action) {}

    // ========================================================================
    // 主动轮询开关状态
    // ========================================================================


    /**
     * 【定时轮询】每轮都实时扫描所有产品，找出物模型中有 ge_ 属性的产品，向其下设备发送状态查询。
     *
     * <p>每 30 秒执行一次，MQTT 未连接时自动跳过。</p>
     *
     * <p>每轮流程：
     * <ol>
     *   <li>查全量产品列表（Redis 缓存 10 分钟）</li>
     *   <li>逐产品查物模型 → 有 ge_ 属性的加入轮询</li>
     *   <li>对每个匹配产品 → DB 查其下全量设备 → 下发 "zqnetget0qz"</li>
     *   <li>硬件回复 → handleSerialRead → PropertyReport → 规则引擎</li>
     * </ol>
     */
    @Scheduled(fixedRate = Ge100dConstants.POLL_FIXED_RATE_MS)
    public void pollSwitchStatus() {
        // MQTT 未连接则跳过
        if (!verticle.isMqttConnected()) {
            return;
        }

        // 步骤 1: 查全量产品（Redis 缓存，10 分钟过期）
        List<String> allPks = componentServices.getProductApi().getAllProductKeys();
        if (CollUtil.isEmpty(allPks)) {
            log.debug("ge100d polling: no products found");
            return;
        }

        // 步骤 2+3: 筛选物模型中有 ge_ 属性的产品，对其下设备下发命令
        for (String pk : allPks) {
            try {
                if (!hasGeProperty(pk)) {
                    continue;
                }

                List<DeviceInfo> devices = componentServices.getDeviceApi()
                        .getDeviceListByProductKey(pk);
                if (CollUtil.isEmpty(devices)) {
                    continue;
                }

                log.debug("ge100d polling: sending status query to {} devices for pk={}",
                        devices.size(), pk);

                for (DeviceInfo device : devices) {
                    if (device.getProductKey() == null || device.getDn() == null) {
                        continue;
                    }
                    verticle.publishCommand(device.getProductKey(), device.getDn(), Ge100dConstants.STATUS_QUERY_CMD);
                }
            } catch (Exception e) {
                log.error("ge100d pollSwitchStatus error for pk={}", pk, e);
            }
        }
    }

    /**
     * 处理轮询应答：硬件回复 zqnetretXqz（X=0或1）。
     *
     * <ol>
     *   <li>查找设备信息（缓存+DB）</li>
     *   <li>获取产品物模型，找出所有 ge_ 属性</li>
     *   <li>将所有 ge_ 属性统一设为轮询返回的状态值</li>
     *   <li>生成 PropertyReport 上报规则引擎</li>
     * </ol>
     */
    public void handlePollResponse(String pk, String dn, int state) {
        DeviceInfo gw = lookupGateway(pk, dn);
        if (gw == null) return;

        // 从物模型中提取所有 ge_ 属性，统一赋值为轮询状态
        Dict params = buildGeProperties(pk, state);
        if (params.isEmpty()) {
            log.debug("ge100d poll response: no ge_ property for pk={}, dn={}", pk, dn);
            return;
        }

        report(PropertyReport.builder()
                .productKey(gw.getProductKey())
                .deviceName(gw.getDn())
                .params(params)
                .time(System.currentTimeMillis())
                .build());

        log.debug("ge100d poll response: dn={}, state={}, geProperties={}", dn, state, params.keySet());
    }

    /**
     * 从物模型中提取所有 ge_ 属性，填充为指定值。
     * 这里一个物模型针对一个产品 一个产品对应一个真实的设备。所以我的一路继电器只配置一个ge_属性 代表只控制一路继电器,
     * 即使上报的时候没有参数名称 我依然可以知道 这个值是给设备商的哪个参数的，因为一个设备只有一个开关参数
     */
    private Dict buildGeProperties(String pk, int value) {
        Dict params = Dict.create();
        try {
            ThingModel tm = componentServices.getThingModelApi()
                    .getThingModelByProductKeyFromCache(pk);
            if (tm != null && tm.getModel() != null && tm.getModel().getProperties() != null) {
                tm.getModel().getProperties().forEach(prop -> {
                    if (prop.getIdentifier() != null && prop.getIdentifier().startsWith(Ge100dConstants.PROPERTY_PREFIX)) {
                        params.set(prop.getIdentifier(), value);
                    }
                });
            }
        } catch (Exception e) {
            log.error("ge100d buildGeProperties failed pk={}", pk, e);
        }
        return params;
    }

    /**
     * 判断指定产品的物模型中是否包含 identifier 以 "ge_" 开头的属性。
     */
    private boolean hasGeProperty(String productKey) {
        try {
            ThingModel tm = componentServices.getThingModelApi()
                    .getThingModelByProductKeyFromCache(productKey);
            if (tm == null || tm.getModel() == null
                    || tm.getModel().getProperties() == null) {
                return false;
            }
            return tm.getModel().getProperties().stream()
                    .anyMatch(p -> p.getIdentifier() != null
                            && p.getIdentifier().startsWith(Ge100dConstants.PROPERTY_PREFIX));
        } catch (Exception e) {
            log.error("ge100d hasGeProperty error for pk={}", productKey, e);
            return false;
        }
    }
}
