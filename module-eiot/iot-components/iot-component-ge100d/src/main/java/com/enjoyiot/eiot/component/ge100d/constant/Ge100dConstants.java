package com.enjoyiot.eiot.component.ge100d.constant;

/**
 * GE100D 网关透传组件常量
 *
 * <p>集中管理 GE100D 组件中的协议命令、MQTT Topic、客户端参数等常量，
 * 避免魔法字符串/数字散落在业务代码中。</p>
 */
public final class Ge100dConstants {

    private Ge100dConstants() {
    }

    // ==================== 组件信息 ====================

    /** 组件类型标识 */
    public static final String TYPE = "ge100d";

    /** 组件名称 */
    public static final String NAME = "智嵌物联GE100D网关透传组件";

    // ==================== 物模型属性前缀 ====================

    /** 属性标识符前缀：物模型中以 ge_ 开头的属性由 GE100D 组件处理 */
    public static final String PROPERTY_PREFIX = "ge_";

    // ==================== 控制协议 ====================

    /** 状态查询命令：透传给硬件读取继电器状态，硬件回复 zqnetretXqz */
    public static final String STATUS_QUERY_CMD = "zqnetget0qz";

    /** 开启继电器命令 */
    public static final String SET_ON_CMD = "zqnetset1qz";

    /** 关闭继电器命令 */
    public static final String SET_OFF_CMD = "zqnetset0qz";

    /** 轮询应答帧前缀：zqnetret0qz → 状态=0，zqnetret1qz → 状态=1 */
    public static final String POLL_RESPONSE_PREFIX = "zqnetret";

    /** 轮询应答帧（开）：zqnetret1qz */
    public static final String POLL_RESPONSE_ON = "zqnetret1qz";

    // ==================== MQTT Topic ====================

    /** 上行订阅 Topic：ge100d/{productKey}/{gatewayDn}/serial/up */
    public static final String TOPIC_UP = "ge100d/+/+/serial/up";

    /** 下行 Topic 前缀 */
    public static final String TOPIC_DOWN_PREFIX = "ge100d/";

    /** 下行 Topic 后缀 */
    public static final String TOPIC_DOWN_SUFFIX = "/serial/down";

    /** Topic 按 / 分割后的最小段数（ge100d/{pk}/{dn}/serial/up） */
    public static final int TOPIC_PART_COUNT = 5;

    // ==================== MQTT 客户端 ====================

    /** MQTT ClientId 前缀 */
    public static final String CLIENT_ID_PREFIX = "ge100d_server_";

    /** 断线重连线程名 */
    public static final String CONNECT_THREAD_NAME = "ge100d_connect";

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
