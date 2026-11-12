package com.enjoyiot.eiot.ruleengine.action;

import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.enjoyiot.eiot.engine.IScriptEngine;
import com.enjoyiot.eiot.engine.ScriptEngineFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 【脚本服务】— 告警动作的 JS 脚本执行引擎
 *
 * <h2>职责</h2>
 * 封装 JS 脚本引擎，将 ThingModelMessage + DeviceInfo 作为输入，
 * 执行用户编写的 translate() 函数，返回结构化参数。
 *
 * <h2>执行流程</h2>
 * <pre>
 * execScript(typeRef, msg)
 *   ├── 1. 查询设备信息: deviceApi.getDeviceInfoFromCache(msg.deviceId)
 *   └── 2. 执行脚本: scriptEngine.invokeMethod("translate", msg, deviceInfo)
 *        ↓
 *       用户写的 JS 函数:
 *       function translate(msg, device) {
 *           return { temperature: msg.data.temperature, ... };
 *       }
 *        ↓
 *        返回 Map<String, Object>
 * </pre>
 *
 * <h2>使用方</h2>
 * AlertService 继承本类，用于执行告警触发脚本和恢复脚本。
 * 恢复脚本有独立的 recoverExecutor 实例（与触发脚本引擎隔离）。
 *
 * @see AlertService 子类：告警触发/恢复
 * @see IScriptEngine JS 引擎接口
 */
@Slf4j
@Data
public class ScriptService {

    /** JS 脚本引擎实例（默认 JavaScript） */
    private IScriptEngine scriptEngine = ScriptEngineFactory.getScriptEngine("js");

    /** 用户编写的 JS 脚本内容 */
    private String script;

    /** 设备 API（脚本中可查询设备信息） */
    private DeviceApi deviceApi;

    /**
     * 设置脚本内容并初始化脚本引擎。
     */
    public void setScript(String script) {
        this.script = script;
        scriptEngine.setScript(script);
    }

    /**
     * 【执行脚本】
     *
     * <p>两步走：
     * <ol>
     *   <li>查设备信息（脚本可能需要设备属性/配置做判断）</li>
     *   <li>调用脚本引擎的 translate 方法，传入消息和设备信息</li>
     * </ol>
     *
     * @param type 返回类型引用（如 new TypeReference<Map<String, Object>>() {}）
     * @param msg  物模型消息（含设备上报数据）
     * @return 脚本执行结果，失败返回 null
     */
    public <T> T execScript(TypeReference<T> type, ThingModelMessage msg) {
        try {
            // 查设备信息: 脚本里可以通过 device 参数访问设备属性/配置
            DeviceInfo deviceInfo = deviceApi.getDeviceInfoFromCache(msg.getDeviceId());

            // 执行 translate 函数: translate(msg, deviceInfo) → T
            return scriptEngine.invokeMethod(type, "translate", msg, deviceInfo);
        } catch (Throwable e) {
            log.error("run script error", e);
            return null;
        }
    }
}
