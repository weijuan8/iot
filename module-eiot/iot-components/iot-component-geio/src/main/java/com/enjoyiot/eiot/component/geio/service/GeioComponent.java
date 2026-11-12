package com.enjoyiot.eiot.component.geio.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Dict;
import com.enjoyiot.eiot.component.core.ComponentServices;
import com.enjoyiot.eiot.component.core.ThingComponent;
import com.enjoyiot.eiot.component.core.model.down.*;
import com.enjoyiot.eiot.component.core.model.up.PropertyReport;
import com.enjoyiot.eiot.component.geio.constant.GeioConstants;
import com.enjoyiot.eiot.component.geio.model.GeioConfig;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.RegisterDevice;
import com.enjoyiot.module.eiot.api.module.YModuleInfo;
import com.enjoyiot.module.eiot.api.product.dto.Product;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * <h1>GEIO 多路继电器控制组件</h1>
 *
 * <h2>标识符约定（两个不同的"数字段"各司其职）</h2>
 * <pre>
 * 1. dn（设备唯一标识符）: geio_区域_用户_地址，最后一段数字 = 该设备的地址 addr
 *    dn 其余段只是平台命名/业务标识，不参与协议
 *    例: geio_mlxy_test_01_01 → 最后 _01 → addr=1（每台设备地址可不同，各发各的 addr）
 *
 * 2. 物模型参数唯一标识符: geio_01 ~ geio_16，末段数字 = 继电器位置（产品固定，
 *    产品上哪个位置是哪个开关由它决定）
 *
 * dn 不以 _数字 结尾（解析不出 addr）→ 直接报错并跳过，无默认地址兜底
 * </pre>
 *
 * <h2>协议（JSON，addr 从 dn 末段解析）</h2>
 * <pre>
 * 控制下发: {"addr":设备地址,"cmd":"set","y":[1,null,0,...]}
 *   y 为继电器状态列表，最多 16 位；y[0] 对应第 1 路（geio_01），第 n 位对应 geio_n
 *   位值: 1=开 0=关 null=不控制；y 只保留到最后一个受控位（尾部 null 裁剪）
 *   样例: {"addr":64,"cmd":"set","y":[1,1,1,1,1,1,1,1]}
 *
 * 轮询下发: {"addr":设备地址,"cmd":"get","data":["y"]}   （data 固定写字符串 "y"）
 *
 * 应答上报: {"addr":设备地址,"cmd":"ret","y":[16 路当前状态]}
 *   样例: {"addr":64,"cmd":"ret","y":[1,1,1,1,1,1,1,1,0,0,1,1,1,1,0,0]}
 *   y 按位表示第 1~16 路继电器当前实际状态，收到后依次更新各路开关属性
 * </pre>
 *
 * <h2>通道映射</h2>
 * 物模型参数标识符以 geio_ 开头且末尾带序号，序号即继电器位序：
 * <pre>
 * geio_01 → y[0]（第 1 路） ... geio_16 → y[15]（第 16 路）
 * </pre>
 *
 * <h2>数据流</h2>
 * <pre>
 * 下行 propertySet（一次合并为一条 set，y ≤16 位，尾部 null 裁剪）:
 *   物模型参数 → 序号 → y[序号-1] = 状态（其余 null）
 *     → buildSetCmd → verticle.publishCommand → MQTT → GEIO 硬件
 *
 * 上行 ret 应答（全量 16 路，无需队列）:
 *   GeioVerticle 解析 {"cmd":"ret","y":[...]}
 *     → GeioComponent.handleRet → 遍历物模型 geio_ 属性 → y[序号-1] → PropertyReport
 *
 * 上行主动上报 JSON（设备周期主动上报环境/状态，无 cmd 字段，兼容 ge100d 上报方式）:
 *   {"id":"10c7ec901419101900","time":"2026-09-05 16:14:13","DO":4.48,"tem":28.6,"PH":8.76}
 *     → GeioVerticle 整帧解析为键值对
 *     → GeioComponent.handleSerialRead → 键值对整体 PropertyReport → 规则引擎按物模型匹配落库
 *
 * 定时轮询（10s）:
 *   扫描全量产品 → 物模型含 geio_ 属性的产品
 *     → 对其下每台设备下发一条 get 指令 → 硬件回 ret → handleRet 全量更新
 * </pre>
 */
@Slf4j
@Component
public class GeioComponent extends ThingComponent {

    private final GeioVerticle verticle;
    private GeioConfig config;

    /** 网关缓存: Key="pk:dn", Value=DeviceInfo（key 用 pn+dn 避免不同 pk 同 dn 冲突） */
    private final Map<String, DeviceInfo> gatewayCache = new ConcurrentHashMap<>();

    /**
     * moduleId 缓存：Key="pk:dn", Value=moduleId（仅内存缓存）
     *
     * <p>设备上行 MQTT topic 为 6 段 {@code geio/{pk}/{dn}/{moduleId}/serial/up}，
     * moduleId 由 topic 解析后写入此缓存。下行 poll / control 时从缓存取，组装
     * 下行 topic 6 段 {@code geio/{pk}/{dn}/{moduleId}/serial/down}。</p>
     *
     * <p>不持久化到数据库（设备表无模块 ID 字段），设备重启后重新上报即可刷新。
     * 缓存为空说明该设备从未上报过 → 报错跳过，避免发拼错的 topic。</p>
     */
    private final Map<String, String> moduleIdCache = new ConcurrentHashMap<>();

    /**
     * 同 key 锁：同一 (pn, dn) 首次上报的并发帧走到 lookupOrRegister 时只让一个线程进入自动注册流程，
     * 避免重复 registerDevice 和 bindDeviceInternal。其余线程等待结果复用。
     */
    private final Map<String, Object> gatewayCacheLocks = new ConcurrentHashMap<>();

    public GeioComponent(GeioVerticle verticle, ComponentServices componentServices) {
        super(componentServices);
        this.verticle = verticle;
    }

    @Override public String getType() { return GeioConstants.TYPE; }
    @Override public String getName() { return GeioConstants.NAME; }

    @Override
    public boolean stateChange(boolean enable, String cfg) {
        verticle.setGeioComponent(this);
        if (!enable) {
            // 组件停用：停止 MQTT 服务 + 清空缓存
            verticle.stopServer();
            gatewayCache.clear();
            return true;
        }
        if (cfg == null) return false;
        config = JsonUtils.parseObject(cfg, GeioConfig.class);
        if (config == null) {
            log.error("============= 【GEIO-错误】组件配置解析失败，请检查后台组件配置 JSON 格式是否合法 =============");
            return false;
        }
        verticle.stopServer();
        verticle.startServer(config);
        gatewayCache.clear();
        moduleIdCache.clear();
        return true;
    }

    // ========================================================================
    // 通用设备查找（含自动注册）
    // ========================================================================

    private static String cacheKey(String pk, String dn) {
        return pk + ":" + dn;
    }

    /**
     * 从本地缓存或 DB 查找设备；查不到且 moduleId 非空时，自动注册设备并绑定到对应模块。
     *
     * <p>自动注册三条件（任一不满足返回 null 且不上自动注册）：</p>
     * <ol>
     *   <li>{@code pk} 对应的产品已存在（否则物模型无从挂起，拒绝凭空注册）</li>
     *   <li>{@code moduleId} 对应的模块已存在（否则租户归属无从产生）</li>
     *   <li>{@code dn} 当前不在 eiot_device_info（首次上报）</li>
     * </ol>
     *
     * <p>moduleId 为 null/空表示非自动注册路径（propertySet 等内部触发），此时仅查不注册。</p>
     *
     * @return 设备信息；找不到且不满足自动注册条件时返回 null
     */
    private DeviceInfo lookupOrRegister(String pk, String dn, String moduleId) {
        String key = cacheKey(pk, dn);
        // moduleId 由 topic 携带；缓存下来供下行拼 topic 使用（不入库）
        if (moduleId != null && !moduleId.isEmpty()) {
            moduleIdCache.put(key, moduleId);
        }
        DeviceInfo gw = gatewayCache.get(key);
        if (gw != null) {
            return gw;
        }
        gw = componentServices.getDeviceApi().getDeviceByPkDnByCache(pk, dn);
        if (gw != null) {
            gatewayCache.put(key, gw);
            log.info("【信息】设备已找到：产品pk={}, 设备dn={}, 设备id={}, 缓存模块id={}",
                    pk, dn, gw.getId(), moduleIdCache.get(key));
            return gw;
        }
        // 未在 DB 找到设备，且 moduleId 缺失 → 维持原行为：warn 丢弃
        if (moduleId == null || moduleId.isEmpty()) {
            log.warn("【警告】未识别的设备（缺少模块ID，无法自动注册）：产品pk={}, 设备dn={}", pk, dn);
            return null;
        }
        // 走自动注册流程：同 key 并发帧串行化，避免重复 registerDevice/bindDeviceInternal
        Object lock = gatewayCacheLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 进入临界区后再查一次：可能上一个线程已写入缓存
            gw = gatewayCache.get(key);
            if (gw != null) {
                return gw;
            }
            gw = componentServices.getDeviceApi().getDeviceByPkDnByCache(pk, dn);
            if (gw != null) {
                gatewayCache.put(key, gw);
                log.info("【信息】自动注册并发冲突已解决，设备已存在：产品pk={}, 设备dn={}", pk, dn);
                return gw;
            }
            gw = autoRegister(pk, dn, moduleId);
            if (gw != null) {
                gatewayCache.put(key, gw);
            }
            return gw;
        }
    }

    /**
     * 自动注册设备并绑定到模块。三条件：产品存在、模块存在、调用方已确保 dn 不在 DB。
     *
     * <p>任一条件不满足均返回 null 并记录日志，不抛异常（上行链路需稳态降级）。</p>
     */
    private DeviceInfo autoRegister(String pk, String dn, String moduleId) {
        // 1. 校验产品存在（物模型必须有，否则 ruleEngine 无法匹配）
        ThingModel tm;
        try {
            tm = componentServices.getThingModelApi().getThingModelByProductKeyFromCache(pk);
        } catch (Exception e) {
            tm = null;
        }
        if (tm == null) {
            // 区分「产品不存在」与「产品存在但未配置物模型」，避免排查方向被误导
            Product product = null;
            try {
                product = componentServices.getProductApi().getProductByPkFromCache(pk);
            } catch (Exception e) {
                product = null;
            }
            if (product == null) {
                log.warn("【警告】跳过自动注册，产品不存在：产品pk={}, 设备dn={}", pk, dn);
            } else {
                log.warn("【警告】跳过自动注册，产品未配置物模型（需到产品详情保存一次物模型）：产品pk={}, 产品名={}, 设备dn={}",
                        pk, product.getName(), dn);
            }
            return null;
        }

        // 2. 解析模块 ID 并校验模块存在（必须，否则 y_module_device.owner_tenant_id 无从产生）
        //    topic 第 4 段为模块 ID（y_module.id 数字主键）；非数字视为 topic 结构不合规，
        //    直接拒绝注册，无兜底（结构必须符合规则，便于排查）
        final Long moduleIdLong;
        try {
            moduleIdLong = Long.parseLong(moduleId);
        } catch (NumberFormatException e) {
            log.warn("【警告】跳过自动注册，模块ID不是数字：设备dn={}, 模块ID={}", dn, moduleId);
            return null;
        }
        YModuleInfo module;
        try {
            module = componentServices.getModuleApi().getById(moduleIdLong);
        } catch (Exception e) {
            log.warn("【警告】跳过自动注册，查询模块失败：模块ID={}, 错误信息={}", moduleId, e.getMessage());
            return null;
        }
        if (module == null) {
            log.warn("【警告】跳过自动注册，模块不存在：模块ID={}", moduleId);
            return null;
        }

        // 3. 注册设备（DeviceApi.registerDevice 内部已走 TenantUtils.executeIgnoreResult，无需登录态）
        DeviceInfo device;
        try {
            device = componentServices.getDeviceApi().registerDevice(
                    RegisterDevice.builder()
                            .productKey(pk)
                            .deviceName(dn)
                            .build());
        } catch (Exception e) {
            log.error("============= 【GEIO-错误】自动注册失败（创建设备异常） ============= 产品pk={}, 设备dn={}, 模块ID={}",
                    pk, dn, moduleId, e);
            return null;
        }
        if (device == null || device.getId() == null) {
            log.error("============= 【GEIO-错误】自动注册失败（创建设备返回空ID） ============= 产品pk={}, 设备dn={}", pk, dn);
            return null;
        }

        // 4. 绑定到模块（系统路径，跳过 YModuleServiceImpl.bindDevice 的权限/超管校验）
        try {
            componentServices.getModuleApi().bindDeviceInternal(module.getId(), device.getId());
        } catch (Exception e) {
            // 设备已建但绑定失败：记录错误，由后续人工补绑；不抛异常以免污染上行链路
            log.error("============= 【GEIO-错误】自动注册失败（绑定模块异常） ============= 设备id={}, 模块id={}, 模块ID={}",
                    device.getId(), module.getId(), moduleId, e);
            return device;
        }
        log.info("【信息】自动注册成功：产品pk={}, 设备dn={}, 设备id={}, 模块id={}, 模块ID={}, 租户id={}",
                pk, dn, device.getId(), module.getId(), moduleId, module.getTenantId());
        return device;
    }

    // ========================================================================
    // 上行：ret 应答（全量 16 路）→ 更新物模型所有 geio_ 开关状态
    // ========================================================================

    /**
     * 处理设备应答 ret：{"addr":设备地址,"cmd":"ret","y":[16 路当前状态]}。
     *
     * <p>应答帧带全量 16 路状态，不再是一问一答的单通道应答，因此无需应答归属队列：
     * 直接遍历该产品物模型上所有 geio_ 属性，按位序（第 n 位 → geio_ 序号 n）取出状态，
     * 一次性批量上报（含控制 set 与轮询 get 触发的应答）。</p>
     *
     * @param pk         产品 key
     * @param dn         设备 dn
     * @param moduleId 模块 ID（由 topic 解析；用于自动注册）
     * @param states     y 数组按位解析出的状态列表（元素可为 null，表示该路无效）
     */
    public void handleRet(String pk, String dn, String moduleId, List<Integer> states) {
        DeviceInfo gw = lookupOrRegister(pk, dn, moduleId);
        if (gw == null || states == null || states.isEmpty()) return;

        Dict params = Dict.create();
        for (String identifier : getGeioProperties(pk)) {
            Integer channel = parseChannel(identifier);
            // 序号即位序：第 n 路 → y[n-1]
            if (channel == null || channel > states.size()) continue;
            Integer state = states.get(channel - 1);
            if (state == null) continue;
            if (state != GeioConstants.STATE_ON && state != GeioConstants.STATE_OFF) {
                log.warn("【警告】继电器状态值非法：设备dn={}, 标识符={}, 状态值={}", dn, identifier, state);
                continue;
            }
            params.set(identifier, state);
        }
        if (params.isEmpty()) {
            log.debug("【调试】应答帧未匹配到任何 geio_ 前缀属性：产品pk={}, 设备dn={}", pk, dn);
            return;
        }

        report(PropertyReport.builder()
                .productKey(gw.getProductKey())
                .deviceName(gw.getDn())
                .params(params)
                .time(System.currentTimeMillis())
                .build());
        log.debug("【调试】继电器状态应答：设备dn={}, 状态集合={}", dn, params);
    }

    // ========================================================================
    // 上行：设备主动上报键值 JSON（兼容 ge100d 上报方式）
    // ========================================================================

    /**
     * 处理设备主动上报的键值 JSON 帧。
     *
     * <p>GEIO 设备除应答 ret（继电器全量状态）外，还会周期性主动上报环境/水质类 JSON，
     * 如 {@code {"id":"10c7ec901419101900","time":"2026-09-05 16:14:13","DO":4.4869,
     * "tem":28.6278,"PH":8.7600}}。这类帧无 cmd 字段，不携带继电器状态，与 ge100d
     * 透传组件收到的主动上报格式一致：直接整帧按键值对一次性上报，交由上层规则引擎
     * 按物模型匹配落库，组件本身不做属性过滤。</p>
     *
     * @param pk         产品 key（由 Verticle 从 topic 解析）
     * @param dn         设备 dn（由 Verticle 从 topic 解析）
     * @param moduleId 模块 ID（由 Verticle 从 topic 解析；用于自动注册）
     * @param data       整帧 JSON 展开后的键值对
     */
    public void handleSerialRead(String pk, String dn, String moduleId, Map<String, Object> data) {
        DeviceInfo gw = lookupOrRegister(pk, dn, moduleId);
        if (gw == null || data == null || data.isEmpty()) return;

        Dict params = Dict.create();
        data.forEach(params::set);

        report(PropertyReport.builder()
                .productKey(gw.getProductKey())
                .deviceName(gw.getDn())
                .params(params)
                .time(System.currentTimeMillis())
                .build());
        log.debug("【调试】串口数据上报：设备dn={}, 上报参数={}", dn, params);
    }

    // ========================================================================
    // 主动轮询开关状态
    // ========================================================================

    /**
     * 【定时轮询】每轮实时扫描所有产品，找出物模型中有 geio_ 属性的产品，
     * 对其下每台设备下发一条 get 查询指令，等待设备回复 ret 全量状态。
     *
     * <p>每 10 秒执行一次，MQTT 未连接时自动跳过。</p>
     *
     * <p>每轮流程：
     * <ol>
     *   <li>查全量产品列表（Redis 缓存 10 分钟）</li>
     *   <li>逐产品查物模型 → 有 geio_ 属性的产品加入轮询</li>
     *   <li>对每个匹配产品 → DB 查其下全量设备 → 每台设备下发一条 get 指令</li>
     *   <li>设备回复 ret → handleRet → 全量更新该设备所有 geio_ 开关 → PropertyReport</li>
     * </ol>
     */
    @Scheduled(fixedRate = GeioConstants.POLL_FIXED_RATE_MS)
    public void pollSwitchStatus() {
        // MQTT 未连接则跳过
        if (!verticle.isMqttConnected()) {
            return;
        }

        // 步骤 1: 查全量产品（Redis 缓存，10 分钟过期）
        List<String> allPks = componentServices.getProductApi().getAllProductKeys();
        if (CollUtil.isEmpty(allPks)) {
            log.debug("【调试】轮询任务：未找到任何产品");
            return;
        }

        // 步骤 2+3: 筛选物模型中有 geio_ 属性的产品，对其下每台设备下发一条 get 指令
        for (String pk : allPks) {
            try {
                //获取当前产品所有的 geio 开头的参数
                List<String> identifiers = getGeioProperties(pk);
                if (identifiers.isEmpty()) {
                    continue;
                }

                List<DeviceInfo> devices = componentServices.getDeviceApi()
                        .getDeviceListByProductKey(pk);
                if (CollUtil.isEmpty(devices)) {
                    continue;
                }

                log.debug("【调试】轮询任务：准备向 {} 个设备下发状态查询指令，产品pk={}", devices.size(), pk);

                for (DeviceInfo device : devices) {
                    if (device.getProductKey() == null || device.getDn() == null) {
                        continue;
                    }
                    // 设备地址从设备名解析（geio_xxx_01 → 1）；命名不符合规则直接报错跳过，无兜底
                    int addr;
                    try {
                        addr = GeioConstants.resolveDeviceAddress(device.getDn());
                    } catch (IllegalArgumentException e) {
                        log.error("============= 【GEIO-错误】轮询跳过非法设备 ============= 设备dn={}, 错误信息={}",
                                device.getDn(), e.getMessage());
                        continue;
                    }
                    // moduleId 不入库，仅缓存（由设备上行 topic 携带）；
                    // 缓存为空说明该设备从未上报过 → 报错跳过，硬件侧不会收到包，便于排查
                    String cachedModule = moduleIdCache.get(cacheKey(device.getProductKey(), device.getDn()));
                    if (cachedModule == null || cachedModule.isEmpty()) {
                        log.error("============= 【GEIO-错误】轮询跳过，模块ID未知（设备从未上报过数据） ============= 产品pk={}, 设备dn={}",
                                device.getProductKey(), device.getDn());
                        continue;
                    }
                    verticle.publishCommand(device.getProductKey(), device.getDn(),
                            cachedModule, buildGetCmd(addr));
                }
            } catch (Exception e) {
                log.error("============= 【GEIO-错误】轮询继电器状态异常 ============= 产品pk={}", pk, e);
            }
        }
    }

    /**
     * 从物模型中提取所有 geio_ 属性标识符，按字典序排序保证查询顺序稳定。
     *
     * <p>标识符格式: geio_ + 通道编号，如 geio_01、geio_16</p>
     */
    private List<String> getGeioProperties(String pk) {
        List<String> identifiers = new ArrayList<>();
        try {
            ThingModel tm = componentServices.getThingModelApi()
                    .getThingModelByProductKeyFromCache(pk);
            if (tm != null && tm.getModel() != null && tm.getModel().getProperties() != null) {
                tm.getModel().getProperties().forEach(prop -> {
                    if (prop.getIdentifier() != null
                            && prop.getIdentifier().startsWith(GeioConstants.PROPERTY_PREFIX)) {
                        identifiers.add(prop.getIdentifier());
                    }
                });
            }
        } catch (Exception e) {
            log.error("============= 【GEIO-错误】获取物模型继电器属性失败 ============= 产品pk={}", pk, e);
        }
        identifiers.sort(Comparator.naturalOrder());
        return identifiers;
    }

    // ========================================================================
    // 下行：开关控制
    // ========================================================================

    @Override
    protected void serviceInvoke(ServiceInvoke action) {
    }

    @Override protected void propertySet(PropertySet action) {
        DeviceInfo gw = lookupOrRegister(action.getProductKey(), action.getDeviceName(), null);
        if (gw == null) return;

        // 一次控制合并为一条 set 指令: y 最多 16 位，未控制的位用 null 占位，序列化时裁剪尾部 null
        Integer[] y = new Integer[GeioConstants.MAX_CHANNEL];
        boolean valid = false;
        for (var e : action.getParams().entrySet()) {
            String k = e.getKey();
            // 根据标识符中的序号确定位序：geio_01 → y[0]（第 1 路）
            Integer channel = parseChannel(k);
            if (channel == null) {
                // 标识符不是 xx_数字 结尾 → 不是 GEIO 的继电器参数
                log.error("============= 【GEIO-错误】接收到错误参数，标识符未包含有效通道序号 ============= 标识符={}, 参数值={}", k, e.getValue());
                continue;
            }
            y[channel - 1] = normalizeDoState(e.getValue());
            valid = true;
        }
        if (!valid) {
            log.warn("【警告】属性下发无有效 geio 通道参数：设备dn={}, 下发参数={}",
                    gw.getDn(), action.getParams());
            return;
        }

        // 设备地址从设备名解析（geio_xxx_01 → 1），下发帧 addr 字段随设备变化；
        // 命名不符合规则直接报错并放弃本次下发，不做默认地址兜底
        int addr;
        try {
            addr = GeioConstants.resolveDeviceAddress(gw.getDn());
        } catch (IllegalArgumentException e) {
            log.error("============= 【GEIO-错误】控制指令取消 ============= 设备dn={}, 错误信息={}", gw.getDn(), e.getMessage());
            return;
        }
        String cmd = buildSetCmd(addr, y);
        if (cmd == null) {
            log.warn("【警告】控制指令已跳过，无有效通道：设备dn={}, 下发参数={}",
                    gw.getDn(), action.getParams());
            return;
        }
        log.info("【信息】下发控制指令：设备dn={}, 设备地址={}, 参数={}, 指令={}",
                gw.getDn(), addr, action.getParams(), cmd);
        // moduleId 不入库，仅缓存（由设备上行 topic 携带）；
        // propertySet 由后端主动触发，设备尚未上报过 → 缓存为空时直接报错，不下兜底包
        String cachedModule = moduleIdCache.get(cacheKey(gw.getProductKey(), gw.getDn()));
        if (cachedModule == null || cachedModule.isEmpty()) {
            log.error("============= 【GEIO-错误】控制指令取消，模块ID未知（设备从未上报过数据） ============= 产品pk={}, 设备dn={}",
                    gw.getProductKey(), gw.getDn());
            return;
        }
        verticle.publishCommand(gw.getProductKey(), gw.getDn(), cachedModule, cmd);
    }

    /**
     * 将物模型下发值归一化为继电器状态（1=开 0=关），兼容数字/布尔/字符串写法。
     *
     * @param value 下发值（如 1/0、true/false、"1"/"0"）
     * @return 1=开 0=关
     */
    private int normalizeDoState(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value ? GeioConstants.STATE_ON : GeioConstants.STATE_OFF;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() == 0 ? GeioConstants.STATE_OFF : GeioConstants.STATE_ON;
        }
        if (value == null) {
            return GeioConstants.STATE_OFF;
        }
        String text = value.toString().trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)) {
            return GeioConstants.STATE_ON;
        }
        return GeioConstants.STATE_OFF;
    }

    /**
     * 从标识符中提取通道号：标识符以 _数字 结尾，数字即通道号（1~16）。
     *
     * <p>例: geio_01 → 1（第 1 路），geio_16 → 16（第 16 路）</p>
     *
     * @param identifier 物模型参数标识符
     * @return 通道号（1~16），标识符不符合规则返回 null
     */
    private Integer parseChannel(String identifier) {
        if (identifier == null) return null;
        Matcher m = GeioConstants.CHANNEL_PATTERN.matcher(identifier);
        if (!m.find()) return null;
        int channel = Integer.parseInt(m.group(1));
        if (channel < 1 || channel > GeioConstants.MAX_CHANNEL) {
            log.warn("【警告】通道序号超出范围：标识符={}, 解析出的通道={}", identifier, channel);
            return null;
        }
        return channel;
    }

    /**
     * 拼接 set 控制指令 JSON：{"addr":设备地址,"cmd":"set","y":[1/null/0,...]}。
     *
     * <p>y 数组手动拼装（不用 JSON 库）是为了精确控制 null 占位：
     * y[i]=null 表示第 i+1 路本次不控制，1=开 0=关。
     * y 只保留到最后一个受控位（尾部 null 裁剪），最多 16 位。</p>
     *
     * @param addr 设备地址（从设备名解析，如 geio_mlxy_test_01_01 → 1）
     * @param y    16 位目标状态数组（元素为 1/0/null，至少 1 个非 null）
     * @return 控制指令 JSON 字符串
     */
    private String buildSetCmd(int addr, Integer[] y) {
        // 找到最后一个受控位，尾部 null 无需下发
        int last = y.length - 1;
        while (last >= 0 && y[last] == null) {
            last--;
        }
        if (last < 0) {
            log.error("============= 【GEIO-错误】构建控制指令失败，无有效通道 ============= 设备地址={}", addr);
            return null;
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"addr\":").append(addr)
                .append(",\"cmd\":\"").append(GeioConstants.CMD_SET).append("\",\"y\":[");
        for (int i = 0; i <= last; i++) {
            if (i > 0) sb.append(',');
            sb.append(y[i] == null ? "null" : y[i]);
        }
        return sb.append("]}").toString();
    }

    /**
     * 拼接 get 轮询指令 JSON：{"addr":设备地址,"cmd":"get","data":["y"]}。
     *
     * <p>get 仅查询设备全量继电器状态，data 固定为字符串数组 {@code ["y"]}（设备固件要求，
     * 不是数字占位）；设备收到后回复 ret 全量状态帧。</p>
     *
     * @param addr 设备地址（从设备名解析）
     * @return 查询指令 JSON 字符串
     */
    private String buildGetCmd(int addr) {
        return "{\"addr\":" + addr + ",\"cmd\":\"" + GeioConstants.CMD_GET + "\",\"data\":[\"y\"]}";
    }

    @Override protected void propertyGet(PropertyGet action) {}
    @Override protected void deviceConfig(DeviceConfig action) {}
    @Override protected void deviceOta(DeviceOta action) {}
    @Override protected void deviceTopoChange(DeviceTopoChange action) {}
}
