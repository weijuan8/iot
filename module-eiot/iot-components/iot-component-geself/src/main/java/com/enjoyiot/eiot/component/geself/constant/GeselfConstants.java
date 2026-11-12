package com.enjoyiot.eiot.component.geself.constant;

import com.enjoyiot.framework.common.util.spring.SpringUtils;

import java.util.regex.Pattern;

/**
 * GESELF 继电器控制组件常量
 *
 * <p>协议依据：智嵌物联（GE 系列）串口服务器 JSON 下发/上报协议。</p>
 *
 * <h2>下行协议</h2>
 * <pre>
 * {"mb":"0f050000ff00","sn":1,"ack":1,"crc":1}
 *   mb  : 十六进制 ASCII 字符串，网关转成二进制后发给串口从站
 *   sn  : 数据包顺序（整数）。网关不作为，仅原样回显，用于识别应答与请求的对应关系
 *   ack : 0=网关不上传应答 / 1=网关上传应答（缺省按 1 处理，此处显式下发 1）
 *   crc : 1=mb 不包含 CRC，网关自动计算 CRC / 0=mb 已含 CRC，网关不再计算
 *         （缺省按 0 处理；本协议统一发裸帧 + crc=1，CRC 由网关补齐）
 * </pre>
 *
 * <h2>上行应答</h2>
 * <pre>
 * {"retmb":"0f050000ff008d14","status":"ok","sn":1}
 *   retmb : 串口从站应答帧（含从站自算的 CRC），本组件不做 CRC 校验
 *   status: ok / timeout
 *   sn    : 回显下行指令的 sn，用于与 pending 指令配对
 * </pre>
 */
public final class GeselfConstants {

    private GeselfConstants() {
    }

    // ==================== 组件信息 ====================

    /** 组件类型标识 */
    public static final String TYPE = "geself";

    /** 组件名称 */
    public static final String NAME = "智嵌物联GESELF继电器控制组件";

    // ==================== 物模型属性标识符 ====================

    /**
     * 继电器属性标识符的固定开头：物模型中以 geself 开头的属性才视为继电器开关。
     *
     * <p>标识符中间段数不限，只要以 geself 开头、且以 {@code _序号} 结尾即可，
     * 末段序号即继电器路号，见 {@link #CHANNEL_PATTERN}。</p>
     */
    public static final String PROPERTY_PREFIX = "geself";

    /**
     * 末尾序号提取：取标识符最后一个 {@code _数字}，该数字即继电器路号。
     *
     * <p>geself_rela_01 → 第 1 路，geself_03 → 第 3 路，geself_light_12 → 第 12 路。
     * 序号按十进制解析，前导零自动忽略（01 → 1）。</p>
     *
     * <p>路号由标识符动态决定，不固定 4 路；
     * 超过 {@link #MAX_CHANNEL} 的序号视为非法并丢弃。</p>
     */
    public static final Pattern CHANNEL_PATTERN = Pattern.compile("_(\\d+)$");

    // ==================== JSON 协议字段 ====================

    /** 下行：十六进制串口帧（不含 CRC） */
    public static final String FIELD_MB = "mb";

    /** 下行/上行：数据包顺序，网关回显 */
    public static final String FIELD_SN = "sn";

    /** 下行：是否需要网关上传应答数据 */
    public static final String FIELD_ACK = "ack";

    /** 下行：mb 是否不含 CRC（1=网关自动计算） */
    public static final String FIELD_CRC = "crc";

    /** 上行：串口从站应答帧 */
    public static final String FIELD_RETMB = "retmb";

    /** 上行：应答状态 ok / timeout */
    public static final String FIELD_STATUS = "status";

    /** 应答状态：成功 */
    public static final String STATUS_OK = "ok";

    /** 应答状态：超时（串口从站无响应） */
    public static final String STATUS_TIMEOUT = "timeout";

    /** ack 取值：1=要求网关上传应答数据 */
    public static final int ACK_ON = 1;

    /** crc 取值：1=mb 不含 CRC，由网关自动计算 */
    public static final int CRC_BY_GATEWAY = 1;

    // ==================== Modbus RTU 帧 ====================

    /** 功能码：写单个线圈 */
    public static final String FUNC_WRITE_SINGLE_COIL = "05";

    /** 线圈值：闭合 */
    public static final String COIL_ON = "ff00";

    /** 线圈值：断开 */
    public static final String COIL_OFF = "0000";

    /** 默认继电器板 Modbus 从站地址（2 位十六进制） */
    public static final String DEFAULT_SLAVE_ID = "0f";

    /** 从站地址合法性：2 位十六进制字符 */
    public static final Pattern SLAVE_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{2}$");

    /**
     * 最大继电器通道数（安全上限）。
     *
     * <p>实际路数由物模型标识符末段序号动态决定（geself_rela_01 → 第 1 路，geself_rela_08 → 第 8 路），
     * 本常量仅用于拦截异常标识符（如 geself_999999），防止拼出越界的线圈地址。
     * 若单台设备路数超过该值，直接调大本常量即可。</p>
     */
    public static final int MAX_CHANNEL = 32;

    /** 继电器状态 = 开（1） */
    public static final int STATE_ON = 1;

    /** 继电器状态 = 关（0） */
    public static final int STATE_OFF = 0;

    /** sn 递增上限，超过后回绕到 1（网关只回显不限制，此处仅防止无限增长） */
    public static final int SN_MAX = 65535;

    /**
     * 拼接继电器控制的 MB 帧（不含 CRC，CRC 由网关按 crc=1 自动追加）。
     *
     * <p>帧结构（Modbus RTU 写单线圈）：</p>
     * <pre>
     * | 从站地址 | 功能码 05 | 线圈地址(2 字节) | 线圈值(2 字节) |
     * |   0f    |    05    |      0000       |      ff00      |  第 1 路闭合
     * |   0f    |    05    |      0001       |      0000      |  第 2 路断开
     * </pre>
     *
     * <p>线圈地址 = 路号 - 1（第 1 路 → 0000，第 2 路 → 0001 …）。</p>
     *
     * @param slaveId 继电器板从站地址（2 位十六进制）
     * @param channel 继电器路号（1 起）
     * @param state   {@link #STATE_ON} / {@link #STATE_OFF}
     * @return MB 帧十六进制字符串（小写，不含 CRC）
     */
    public static String buildMbFrame(String slaveId, int channel, int state) {
        String addr = String.format("%04x", channel - 1);
        String value = state == STATE_ON ? COIL_ON : COIL_OFF;
        return slaveId + FUNC_WRITE_SINGLE_COIL + addr + value;
    }

    // ==================== MQTT Topic ====================

    /**
     * 上行订阅 Topic：geself/{productKey}/{deviceName}/{moduleId}/serial/up
     *
     * <p>根据环境返回不同值：生产环境（prod）为 {@code geself/+/+/+/serial/up}；
     * 测试环境（非 prod）把开头换成 testgeself，即 {@code testgeself/+/+/+/serial/up}。
     * 两者段数一致（均为 6 段）：[0]=环境前缀, [1]=pk, [2]=dn, [3]=moduleId, [4]=serial, [5]=up。
     * moduleId 用于设备自动注册（与 geio 组件一致）。</p>
     */
    public static final String TOPIC_UP = SpringUtils.isProd()
            ? "geself/+/+/+/serial/up"
            : "testgeself/+/+/+/serial/up";

    /** 下行 Topic 前缀：生产环境 geself/，测试环境 testgeself/ */
    public static final String TOPIC_DOWN_PREFIX = SpringUtils.isProd()
            ? "geself/"
            : "testgeself/";

    /** 下行 Topic 后缀，按 {prefix}{pk}/{dn}/{moduleId}{suffix} 拼接 */
    public static final String TOPIC_DOWN_SUFFIX = "/serial/down";

    /** Topic 按 / 分割后的最小段数 */
    public static final int TOPIC_PART_COUNT = 6;

    // ==================== MQTT 客户端 ====================

    /** MQTT ClientId 前缀 */
    public static final String CLIENT_ID_PREFIX = "geself_server_";

    /** 断线重连线程名 */
    public static final String CONNECT_THREAD_NAME = "geself_connect";

    /** 多路拆分下发的串行发送线程名 */
    public static final String SEND_THREAD_NAME = "geself_send";

    /** 首次连接延迟 / 断线重连间隔（秒） */
    public static final int RECONNECT_DELAY_SECONDS = 3;

    /** MQTT KeepAlive 心跳间隔（秒） */
    public static final int KEEP_ALIVE_SECONDS = 60;

    /** MQTT MaxInflightQueue：允许同时处于已发送未确认状态的最大消息数 */
    public static final int MAX_INFLIGHT_QUEUE = 10000;

    // ==================== 下发控制 ====================

    /** 同一设备多路拆分下发时相邻帧的间隔（毫秒），给串口从站留出处理时间 */
    public static final long COMMAND_INTERVAL_MS = 150L;

    /** 等待设备 retmb 应答的超时时间（毫秒），超时后清理 pending 并告警 */
    public static final long ACK_TIMEOUT_MS = 10_000L;

    /** pending 指令超时扫描频率（毫秒） */
    public static final long PENDING_CHECK_FIXED_RATE_MS = 30_000L;
}
