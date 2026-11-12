package com.enjoyiot.eiot.component.geself.service;

import cn.hutool.core.lang.Dict;
import com.enjoyiot.eiot.component.core.ComponentServices;
import com.enjoyiot.eiot.component.core.ThingComponent;
import com.enjoyiot.eiot.component.core.model.down.*;
import com.enjoyiot.eiot.component.core.model.up.PropertyReport;
import com.enjoyiot.eiot.component.geself.constant.GeselfConstants;
import com.enjoyiot.eiot.component.geself.model.GeselfConfig;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.framework.common.util.thread.ThreadUtil;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

/**
 * <h1>GESELF 继电器控制组件</h1>
 *
 * <p>设备为智嵌物联（GE 系列）单串口服务器 + 继电器板，平台侧通过 MQTT 下发 JSON 控制帧，
 * 网关转成 Modbus RTU 帧发给串口继电器板。</p>
 *
 * <h2>标识符约定</h2>
 * <pre>
 * 物模型参数唯一标识符: geself_rela_01、geself_rela_02 …
 *   规则: 以 geself 开头即可，中间段数不限；末段序号 = 继电器路号
 *   （geself_rela_01 → 第 1 路，geself_03 → 第 3 路）
 *   路号动态随标识符变化，不固定 4 路；超过 {@link GeselfConstants#MAX_CHANNEL} 的序号视为非法并丢弃
 *
 * dn（设备名）: 仅作平台唯一标识，不参与协议（与 geio 不同，本组件无需从 dn 解析设备地址）
 * </pre>
 *
 * <h2>协议（JSON）</h2>
 * <pre>
 * 控制下发: {"mb":"0f050000ff00","sn":7,"ack":1,"crc":1}
 *   mb  : 从站地址(0f) + 功能码(05) + 线圈地址(路号-1,4 位 hex) + 线圈值(ff00 闭合 / 0000 断开)
 *         不含 CRC；crc=1 表示由网关自动计算并追加
 *   sn  : 数据包顺序，网关原样回显，用于应答配对
 *   ack : 1=要求网关上传应答
 *   crc : 1=mb 不含 CRC
 *
 * 网关应答: {"retmb":"0f050000ff008d14","status":"ok","sn":7}
 *   retmb 为串口从站应答帧（自带 CRC），本组件不校验；status 为 ok / timeout
 *
 * 主动上报: {"tem":28.6,"PH":8.76,...}  —— 无 retmb/status，整帧键值对上报（与 geio 一致）
 * </pre>
 *
 * <h2>数据流</h2>
 * <pre>
 * 下行 propertySet（一路一帧，串行下发）:
 *   每个物模型参数 → 路号 → 拼 MB 帧 → 取递增 sn → {"mb":..,"sn":..,"ack":1,"crc":1}
 *     → 登记 pendingCommands[sn] → verticle.publishCommand → MQTT → 网关
 *
 * 上行应答 ack（一问一答，按 sn 配对）:
 *   GeselfVerticle 解析 {"retmb":..,"status":..,"sn":..}
 *     → GeselfComponent.handleAck → pendingCommands.remove(sn)
 *     → status==ok → PropertyReport(该路标识符, 目标值)
 *     → status!=ok（timeout 等）→ 不上报，仅告警（保持设备侧真实状态不被乐观写脏）
 *
 * 上行主动上报（上报逻辑与 geio 完全一致）:
 *   GeselfVerticle 整帧解析为键值对
 *     → handleSerialRead → 键值对整体 PropertyReport → 规则引擎按物模型匹配落库
 * </pre>
 *
 * <p>说明：本组件不做轮询。网关侧自带 Modbus 轮询任务并按配置周期主动上报数据。</p>
 */
@Slf4j
@Component
public class GeselfComponent extends ThingComponent {

    private final GeselfVerticle verticle;
    private GeselfConfig config;

    /** 设备缓存: Key="pk:dn", Value=DeviceInfo（key 用 pk+dn 避免不同 pk 同 dn 冲突） */
    private final Map<String, DeviceInfo> gatewayCache = new ConcurrentHashMap<>();

    /**
     * moduleId 缓存：Key="pk:dn", Value=moduleId（仅内存缓存）
     *
     * <p>设备上行 MQTT topic 为 6 段 {@code geself/{pk}/{dn}/{moduleId}/serial/up}，
     * moduleId 由 topic 解析后写入此缓存。下行时从缓存取，组装下行 topic 6 段。</p>
     *
     * <p>不持久化到数据库（设备表无模块 ID 字段），设备重启后重新上报即可刷新。
     * 缓存为空说明该设备从未上报过 → 报错跳过，避免发拼错的 topic。</p>
     */
    private final Map<String, String> moduleIdCache = new ConcurrentHashMap<>();

    /**
     * 同 key 锁：同一 (pk, dn) 首次上报的并发帧走到 lookupOrRegister 时只让一个线程进入自动注册流程，
     * 避免重复 registerDevice 和 bindDeviceInternal。
     */
    private final Map<String, Object> gatewayCacheLocks = new ConcurrentHashMap<>();

    /** sn 序列：每设备独立自增（网关只回显 sn，不校验连续性） */
    private final Map<String, AtomicInteger> snSequences = new ConcurrentHashMap<>();

    /**
     * 待确认指令：Key="pk:dn:sn", Value={@link PendingCommand}
     *
     * <p>下行每路登记一条，收到对应 sn 的应答后移除；超时由
     * {@link #cleanExpiredPending()} 清理。</p>
     */
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    /**
     * 串行下发线程：多路控制帧需带间隔下发，避免阻塞消息消费线程。
     */
    private final ScheduledThreadPoolExecutor sendExecutor =
            ThreadUtil.newScheduled(1, GeselfConstants.SEND_THREAD_NAME);

    public GeselfComponent(GeselfVerticle verticle, ComponentServices componentServices) {
        super(componentServices);
        this.verticle = verticle;
    }

    @Override public String getType() { return GeselfConstants.TYPE; }
    @Override public String getName() { return GeselfConstants.NAME; }

    @Override
    public boolean stateChange(boolean enable, String cfg) {
        verticle.setGeselfComponent(this);
        if (!enable) {
            // 组件停用：停止 MQTT 服务 + 清空缓存
            verticle.stopServer();
            clearRuntimeState();
            return true;
        }
        if (cfg == null) return false;
        config = JsonUtils.parseObject(cfg, GeselfConfig.class);
        if (config == null) {
            log.error("============= 【GESELF-错误】组件配置解析失败，请检查后台组件配置 JSON 格式是否合法 =============");
            return false;
        }
        verticle.stopServer();
        verticle.startServer(config);
        clearRuntimeState();
        return true;
    }

    private void clearRuntimeState() {
        gatewayCache.clear();
        moduleIdCache.clear();
        snSequences.clear();
        pendingCommands.clear();
    }

    // ========================================================================
    // 通用设备查找（含自动注册）
    // ========================================================================

    private static String cacheKey(String pk, String dn) {
        return pk + ":" + dn;
    }

    private static String pendingKey(String pk, String dn, int sn) {
        return pk + ":" + dn + ":" + sn;
    }

    /**
     * 从本地缓存或 DB 查找设备；查不到且 moduleId 非空时，自动注册设备并绑定到对应模块。
     *
     * <p>自动注册三条件（任一不满足返回 null 且不注册）：</p>
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
     * 自动注册设备并绑定到模块。调用方已确保 dn 不在 DB。
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

        // 2. 解析模块 ID 并校验模块存在（必须，否则设备归属租户无从产生）
        //    topic 第 4 段为模块 ID（y_module.id 数字主键）；非数字视为 topic 结构不合规
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
            log.error("============= 【GESELF-错误】自动注册失败（创建设备异常） ============= 产品pk={}, 设备dn={}, 模块ID={}",
                    pk, dn, moduleId, e);
            return null;
        }
        if (device == null || device.getId() == null) {
            log.error("============= 【GESELF-错误】自动注册失败（创建设备返回空ID） ============= 产品pk={}, 设备dn={}", pk, dn);
            return null;
        }

        // 4. 绑定到模块（系统路径，跳过 YModuleServiceImpl.bindDevice 的权限/超管校验）
        try {
            componentServices.getModuleApi().bindDeviceInternal(module.getId(), device.getId());
        } catch (Exception e) {
            // 设备已建但绑定失败：记录错误，由后续人工补绑；不抛异常以免污染上行链路
            log.error("============= 【GESELF-错误】自动注册失败（绑定模块异常） ============= 设备id={}, 模块id={}",
                    device.getId(), module.getId(), e);
            return device;
        }
        log.info("【信息】自动注册成功：产品pk={}, 设备dn={}, 设备id={}, 模块id={}, 租户id={}",
                pk, dn, device.getId(), module.getId(), module.getTenantId());
        return device;
    }

    // ========================================================================
    // 上行：网关应答 ack → 按 sn 配对 → 成功才上报该路开关状态
    // ========================================================================

    /**
     * 处理网关应答帧：{"retmb":"0f050000ff008d14","status":"ok","sn":7}。
     *
     * <p>应答与下行指令通过 {@code sn} 配对：网关只原样回显下行帧里的 sn，
     * 据此从 {@link #pendingCommands} 取出原始指令的目标标识符与目标状态。</p>
     *
     * <p>仅在 {@code status == ok} 时才上报属性——网关返回 timeout 说明串口从站没响应，
     * 继电器实际状态并未改变，若乐观上报会写脏设备影子。</p>
     *
     * <p>retmb 为串口从站应答原始帧（自带 CRC），本组件不校验 CRC，仅用于日志排障。</p>
     *
     * @param pk       产品 key
     * @param dn       设备 dn
     * @param moduleId 模块 ID（由 topic 解析；用于自动注册）
     * @param retmb    串口从站应答帧（含 CRC，未校验）
     * @param status   应答状态：ok / timeout
     * @param sn       回显的下行数据包序号
     */
    public void handleAck(String pk, String dn, String moduleId, String retmb, String status, int sn) {
        DeviceInfo gw = lookupOrRegister(pk, dn, moduleId);
        if (gw == null) return;

        PendingCommand pending = pendingCommands.remove(pendingKey(pk, dn, sn));
        if (pending == null) {
            // 无对应待确认指令：可能是轮询/其它路径触发的应答，或 pending 已被超时清理
            log.warn("【警告】收到未知 sn 的应答帧，已忽略：设备dn={}, 数据包序号sn={}, 状态={}, 应答帧={}",
                    dn, sn, status, retmb);
            return;
        }
        if (!GeselfConstants.STATUS_OK.equals(status)) {
            log.warn("【警告】控制指令未成功应答：设备dn={}, 标识符={}, 状态={}, 数据包序号sn={}, 应答帧={}",
                    dn, pending.identifier, status, sn, retmb);
            return;
        }

        report(PropertyReport.builder()
                .productKey(gw.getProductKey())
                .deviceName(gw.getDn())
                .params(Dict.create().set(pending.identifier, pending.state))
                .time(System.currentTimeMillis())
                .build());
        log.info("【信息】控制指令应答成功：设备dn={}, 标识符={}, 目标状态={}, 数据包序号sn={}, 应答帧={}",
                dn, pending.identifier, pending.state, sn, retmb);
    }

    // ========================================================================
    // 上行：设备主动上报键值 JSON（与 geio 上报逻辑完全一致）
    // ========================================================================

    /**
     * 处理设备主动上报的键值 JSON 帧。
     *
     * <p>设备除响应控制指令外，还会周期性主动上报环境/状态类 JSON，
     * 如 {@code {"tem":28.6278,"PH":8.7600}}。这类帧不带应答字段，
     * 直接整帧按键值对一次性上报，交由上层规则引擎按物模型匹配落库，组件不做属性过滤。</p>
     *
     * @param pk       产品 key（由 Verticle 从 topic 解析）
     * @param dn       设备 dn（由 Verticle 从 topic 解析）
     * @param moduleId 模块 ID（由 Verticle 从 topic 解析；用于自动注册）
     * @param data     整帧 JSON 展开后的键值对
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
    // 下行：继电器开关控制
    // ========================================================================

    @Override
    protected void serviceInvoke(ServiceInvoke action) {
    }

    /**
     * 下发继电器控制指令。
     *
     * <p>协议规定一条 JSON 只能携带一个 {@code mb} 帧、控制一路继电器，
     * 因此一次 propertySet 携带多个参数时，按参数逐路拆成多条独立指令，
     * 每条各自取一个递增 {@code sn}，串行带间隔下发。</p>
     *
     * <p>下发的同时登记 {@link #pendingCommands}，待网关应答回来后再上报实际状态。</p>
     */
    @Override
    protected void propertySet(PropertySet action) {
        DeviceInfo gw = lookupOrRegister(action.getProductKey(), action.getDeviceName(), null);
        if (gw == null) return;

        // moduleId 不入库，仅缓存（由设备上行 topic 携带）；
        // 缓存为空说明该设备从未上报过 → 直接报错，避免发拼错的 topic
        final String pk = gw.getProductKey();
        final String dn = gw.getDn();
        String cachedModule = moduleIdCache.get(cacheKey(pk, dn));
        if (cachedModule == null || cachedModule.isEmpty()) {
            log.error("============= 【GESELF-错误】控制指令取消，模块ID未知（设备从未上报过数据） ============= 产品pk={}, 设备dn={}", pk, dn);
            return;
        }

        final String relayPrefix = config == null
                ? GeselfConstants.PROPERTY_PREFIX : config.effectiveRelayPrefix();

        // 解析出本次要控制的各路：[标识符, 路号, 目标状态]
        List<Object[]> targets = new ArrayList<>();
        for (var e : action.getParams().entrySet()) {
            String k = e.getKey();
            // 固定开头不匹配的直接跳过：防止物模型中其它「_数字」结尾的参数被误控
            if (k == null || !k.startsWith(relayPrefix)) {
                log.warn("【警告】跳过非继电器参数：设备dn={}, 标识符={}, 继电器固定开头={}",
                        dn, k, relayPrefix);
                continue;
            }
            // 末段序号即路号：geself_rela_01 → 第 1 路
            Integer channel = parseChannel(k);
            if (channel == null) {
                // 标识符不是 geself…_数字 结尾 → 不是本组件的继电器参数
                log.error("============= 【GESELF-错误】接收到错误参数，标识符未包含有效通道序号 ============= 标识符={}, 参数值={}", k, e.getValue());
                continue;
            }
            targets.add(new Object[]{k, channel, normalizeDoState(e.getValue())});
        }
        if (targets.isEmpty()) {
            log.warn("【警告】属性下发无有效通道参数：设备dn={}, 下发参数={}", dn, action.getParams());
            return;
        }

        final String slaveId = config == null ? GeselfConstants.DEFAULT_SLAVE_ID : config.effectiveSlaveId();
        final long interval = config == null ? GeselfConstants.COMMAND_INTERVAL_MS : config.getCommandIntervalMs();
        final long ackTimeout = config == null ? GeselfConstants.ACK_TIMEOUT_MS : config.getAckTimeoutMs();
        final String deviceKey = cacheKey(pk, dn);

        // 串行异步下发：带间隔，避免阻塞消息消费线程
        sendExecutor.execute(() -> {
            for (Object[] t : targets) {
                String identifier = (String) t[0];
                int channel = (Integer) t[1];
                int state = (Integer) t[2];
                try {
                    String mb = GeselfConstants.buildMbFrame(slaveId, channel, state);
                    int sn = nextSn(deviceKey);
                    String cmd = buildDownCmd(mb, sn);
                    // 先登记再下发，避免 MQTT 成功但登记未完成时应答先到
                    pendingCommands.put(pendingKey(pk, dn, sn),
                            new PendingCommand(identifier, state, System.currentTimeMillis()));
                    verticle.publishCommand(pk, dn, cachedModule, cmd);
                    if (interval > 0) {
                        Thread.sleep(interval);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("============= 【GESELF-错误】指令下发线程被中断 ============= 设备dn={}, 标识符={}", dn, identifier);
                    return;
                } catch (Exception ex) {
                    log.error("============= 【GESELF-错误】指令下发异常 ============= 设备dn={}, 标识符={}", dn, identifier, ex);
                }
            }
            log.debug("【调试】控制指令已派发：设备dn={}, 指令条数={}, 应答超时={}毫秒", dn, targets.size(), ackTimeout);
        });
    }

    /**
     * 拼接下发指令 JSON：{"mb":"0f050000ff00","sn":7,"ack":1,"crc":1}。
     *
     * @param mb 单路控制的 MB 帧（不含 CRC）
     * @param sn 数据包序号，网关原样回显用于应答配对
     * @return 下发 JSON 字符串
     */
    private String buildDownCmd(String mb, int sn) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"").append(GeselfConstants.FIELD_MB).append("\":\"").append(mb)
                .append("\",\"").append(GeselfConstants.FIELD_SN).append("\":").append(sn)
                .append(",\"").append(GeselfConstants.FIELD_ACK).append("\":").append(currentAck())
                .append(",\"").append(GeselfConstants.FIELD_CRC).append("\":").append(currentCrc())
                .append("}");
        return sb.toString();
    }

    private int currentAck() {
        if (config == null) return GeselfConstants.ACK_ON;
        return config.getAck() == 0 ? GeselfConstants.ACK_ON : 1;
    }

    private int currentCrc() {
        // 本组件始终下发不含 CRC 的裸帧，由网关补算，固定为 1
        return GeselfConstants.CRC_BY_GATEWAY;
    }

    /**
     * 取设备的下一个 sn（每设备独立自增，超过 {@link GeselfConstants#SN_MAX} 回绕到 1）。
     */
    private int nextSn(String deviceKey) {
        AtomicInteger seq = snSequences.computeIfAbsent(deviceKey, k -> new AtomicInteger(0));
        int sn = seq.incrementAndGet();
        if (sn > GeselfConstants.SN_MAX) {
            seq.set(0);
            sn = seq.incrementAndGet();
        }
        return sn;
    }

    /**
     * 将物模型下发值归一化为继电器状态（1=开 0=关），兼容数字/布尔/字符串写法。
     *
     * @param value 下发值（如 1/0、true/false、"1"/"0"）
     * @return 1=开 0=关
     */
    private int normalizeDoState(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value ? GeselfConstants.STATE_ON : GeselfConstants.STATE_OFF;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() == 0 ? GeselfConstants.STATE_OFF : GeselfConstants.STATE_ON;
        }
        if (value == null) {
            return GeselfConstants.STATE_OFF;
        }
        String text = value.toString().trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)) {
            return GeselfConstants.STATE_ON;
        }
        return GeselfConstants.STATE_OFF;
    }

    /**
     * 从标识符末段提取路号：取最后一个 {@code _数字}，该数字即路号
     * （geself_rela_01 → 第 1 路，geself_light_12 → 第 12 路）。
     *
     * <p>序号按十进制解析，前导零自动忽略（01 → 1）。</p>
     *
     * @param identifier 物模型参数标识符
     * @return 路号（≥1），标识符无末段序号或超出上限返回 null
     */
    private Integer parseChannel(String identifier) {
        if (identifier == null) return null;
        Matcher m = GeselfConstants.CHANNEL_PATTERN.matcher(identifier);
        if (!m.find()) return null;
        int channel;
        try {
            channel = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            log.warn("【警告】通道序号数字溢出：标识符={}", identifier);
            return null;
        }
        if (channel < 1 || channel > GeselfConstants.MAX_CHANNEL) {
            log.warn("【警告】通道序号超出范围：标识符={}, 解析出的通道={}", identifier, channel);
            return null;
        }
        return channel;
    }

    // ========================================================================
    // 待确认指令超时清理
    // ========================================================================

    /**
     * 清理超时未收到应答的 pending 指令。
     *
     * <p>正常情况下应答到达即 remove；仅当网关离线、串口从站无响应（status=timeout
     * 且帧丢失）时才残留，到期清理防止内存无限增长。</p>
     */
    @Scheduled(fixedRate = GeselfConstants.PENDING_CHECK_FIXED_RATE_MS)
    public void cleanExpiredPending() {
        if (pendingCommands.isEmpty()) return;
        long timeout = config == null ? GeselfConstants.ACK_TIMEOUT_MS : config.getAckTimeoutMs();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingCommand>> it = pendingCommands.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingCommand> e = it.next();
            PendingCommand p = e.getValue();
            if (now - p.timestamp > timeout) {
                log.warn("【警告】控制指令应答超时，已移除待确认记录：key={}, 标识符={}, 目标状态={}",
                        e.getKey(), p.identifier, p.state);
                it.remove();
            }
        }
    }

    @Override protected void propertyGet(PropertyGet action) {}
    @Override protected void deviceConfig(DeviceConfig action) {}
    @Override protected void deviceOta(DeviceOta action) {}
    @Override protected void deviceTopoChange(DeviceTopoChange action) {}

    /**
     * 一条已下发的继电器指令，等待网关按 sn 回执。
     */
    private static class PendingCommand {
        /** 目标物模型标识符，如 geself_rela_01 */
        final String identifier;
        /** 目标状态：STATE_ON / STATE_OFF */
        final int state;
        /** 下发时间戳（毫秒），用于超时清理 */
        final long timestamp;

        PendingCommand(String identifier, int state, long timestamp) {
            this.identifier = identifier;
            this.state = state;
            this.timestamp = timestamp;
        }
    }
}
