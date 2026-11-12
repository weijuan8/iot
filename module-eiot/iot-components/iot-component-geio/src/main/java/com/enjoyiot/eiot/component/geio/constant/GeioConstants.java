package com.enjoyiot.eiot.component.geio.constant;

import com.enjoyiot.framework.common.util.spring.SpringUtils;

import java.util.regex.Pattern;

/**
 * GEIO 多路继电器控制组件常量
 *
 * <p>集中管理 GEIO 组件中的 JSON 协议字段、通道映射、MQTT Topic、客户端参数等常量，
 * 避免魔法字符串/数字散落在业务代码中。</p>
 */
public final class GeioConstants {

    private GeioConstants() {
    }

    // ==================== 组件信息 ====================

    /** 组件类型标识 */
    public static final String TYPE = "geio";

    /** 组件名称 */
    public static final String NAME = "智嵌物联GEIO多路继电器控制组件";

    // ==================== 物模型属性前缀 ====================
    /** 属性标识符前缀：物模型中以 geio_ 开头的属性为继电器开关，末尾编号即通道号（如 geio_01 → 第 1 路） */
    public static final String PROPERTY_PREFIX = "geio_";

    /** 末尾序号提取：xxx_数字 结尾。物模型标识符 geio_01 → 通道 01（设备名地址解析见 {@link #resolveDeviceAddress}） */
    public static final Pattern CHANNEL_PATTERN = Pattern.compile("_(\\d+)$");

    // ==================== JSON 协议 ====================

    /**
     * 从设备名解析设备地址（作为协议 JSON 的 {@code addr} 字段）。
     *
     * <p>设备命名规则：设备名由 {@code _} 分隔，最后一个 {@code _} 之后的数字段即为设备地址。
     * 例：{@code geio_mlxy_test_01_01} → 地址 {@code 1}；{@code geio_01} → 地址 {@code 1}。</p>
     *
     * <p>解析失败（设备名为空、不含 {@code _}、末段为空或非纯数字）统一抛出
     * {@link IllegalArgumentException}，由调用方记录错误并跳过本次操作，不做默认地址兜底。</p>
     *
     * @param deviceName 设备名（dn）
     * @return 设备地址（最后一个 {@code _} 之后的数字段解析出的整数）
     * @throws IllegalArgumentException 无法从设备名解析出地址时抛出
     */
    public static int resolveDeviceAddress(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) {
            throw new IllegalArgumentException("device name is null or empty, cannot resolve geio address");
        }
        int idx = deviceName.lastIndexOf('_');
        if (idx < 0 || idx == deviceName.length() - 1) {
            throw new IllegalArgumentException(
                    "device name must end with '_<digits>', cannot resolve geio address: " + deviceName);
        }
        String tail = deviceName.substring(idx + 1);
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "device name tail is not a pure number, cannot resolve geio address: " + deviceName, e);
        }
    }

    /** 控制命令关键字 */
    public static final String CMD_SET = "set";

    /** 查询命令关键字（定时轮询） */
    public static final String CMD_GET = "get";

    /** 应答命令关键字（设备状态全量上报） */
    public static final String CMD_RET = "ret";

    /** 最大继电器通道数：y 数组固定 16 位，第 1~16 路 */
    public static final int MAX_CHANNEL = 16;

    /** 继电器状态 = 开（1） */
    public static final int STATE_ON = 1;

    /** 继电器状态 = 关（0） */
    public static final int STATE_OFF = 0;

    // ==================== MQTT Topic ====================

    /**
     * 上行订阅 Topic：geio/{productKey}/{gatewayDn}/{moduleId}/serial/up
     *
     * <p>根据环境返回不同值：生产环境（prod）为 {@code geio/+/+/+/serial/up}；
     * 测试环境（非 prod）把开头的 geio 换成 testgeio，即 {@code testgeio/+/+/+/serial/up}。
     * 两者段数一致（均为 6 段）：[0]=环境前缀, [1]=pk, [2]=dn, [3]=moduleId, [4]=serial, [5]=up。
     * moduleId 用于 GEIO 设备自动注册：硬件在 publish topic 时携带该字段，
     * 服务端据此校验模块是否存在并自动绑定设备到对应模块。</p>
     */
    public static final String TOPIC_UP = SpringUtils.isProd()
            ? "geio/+/+/+/serial/up"
            : "testgeio/+/+/+/serial/up";

    /**
     * 下行 Topic 前缀：生产环境 geio/，测试环境 testgeio/
     *
     * <p>与上行 {@link #TOPIC_UP} 保持同一环境逻辑，最终下行 Topic 为
     * {前缀}{pk}/{dn}/{moduleId}/serial/down。</p>
     */
    public static final String TOPIC_DOWN_PREFIX = SpringUtils.isProd()
            ? "geio/"
            : "testgeio/";

    /** 下行 Topic 后缀（不含 /serial/down 前面的 moduleId 段，由调用方按 {prefix} / pk / dn / moduleId / suffix 拼接） */
    public static final String TOPIC_DOWN_SUFFIX = "/serial/down";

    /** Topic 按 / 分割后的最小段数（geio/{pk}/{dn}/{moduleId}/serial/up） */
    public static final int TOPIC_PART_COUNT = 6;

    // ==================== MQTT 客户端 ====================

    /** MQTT ClientId 前缀 */
    public static final String CLIENT_ID_PREFIX = "geio_server_";

    /** 断线重连线程名 */
    public static final String CONNECT_THREAD_NAME = "geio_connect";

    /** 首次连接延迟 / 断线重连间隔（秒） */
    public static final int RECONNECT_DELAY_SECONDS = 3;

    /** MQTT KeepAlive 心跳间隔（秒） */
    public static final int KEEP_ALIVE_SECONDS = 60;

    /** MQTT MaxInflightQueue：允许同时处于已发送未确认状态的最大消息数 */
    public static final int MAX_INFLIGHT_QUEUE = 10000;

    // ==================== 定时轮询 ====================

    /** 开关状态轮询频率（毫秒） */
    public static final long POLL_FIXED_RATE_MS = 30_000L;
}
