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
package com.enjoyiot.module.eiot.controller.admin.iot;


import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.module.eiot.controller.admin.iot.vo.GetDeviceServicePropertyBo;
import com.enjoyiot.module.eiot.controller.admin.iot.vo.DeviceIdReqVo;
import com.enjoyiot.module.eiot.controller.admin.iot.vo.ServiceInvokeReqVo;
import com.enjoyiot.module.eiot.controller.admin.iot.vo.SetDeviceServicePropertyBo;
import com.enjoyiot.module.eiot.service.device.DeviceCtrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

/**
 * 【设备控制 Controller】- 下行消息（平台 → 设备）的 HTTP REST 入口
 *
 * 本 Controller 是整个下行消息链路的起点，接收来自管理后台/API 的设备控制请求。
 * 所有接口都经过权限校验（@PreAuthorize），仅拥有 "iot:device:ctrl" 权限的用户可访问。
 *
 * 【支持的四种下行操作】
 *  ┌────────────────────┬──────────────┬──────────────────────────────────────┐
 *  │ 接口               │ HTTP Method  │ 作用                                  │
 *  ├────────────────────┼──────────────┼──────────────────────────────────────┤
 *  │ /service/invoke    │ POST         │ 服务调用：触发设备执行某个动作         │
 *  │ /service/property/get │ POST      │ 属性获取：请求设备上报当前属性值       │
 *  │ /service/property/set │ POST      │ 属性设置：修改设备的属性值             │
 *  │ /config/send       │ POST         │ 配置下发：将配置参数推送给设备         │
 *  └────────────────────┴──────────────┴──────────────────────────────────────┘
 *
 * 【完整下行链路概览】
 *  DeviceCtrlController（HTTP 入口）
 *    → DeviceCtrlServiceImpl（业务层，构造 ThingModelMessage）
 *      → ComponentManager.sendToDevice()（Redis 路由查找）
 *        → MQ 发布到 {componentType}_device_thing topic
 *          → AbstractComponent.handler()（组件侧路由匹配）
 *            → ThingComponent.onMessage()（消息类型分发）
 *              → EmqxComponent/MqttComponent.publish()（协议下发）
 *                → 设备（MQTT/TCP/HTTP）
 *
 * @see DeviceCtrlService 设备控制服务接口
 * @see DeviceCtrlServiceImpl 设备控制服务实现
 */
@Tag(name = "管理后台-设备控制接口")
@Slf4j
@RestController
@RequestMapping({"/eiot/device", "/eiot/device-ctrl"})
public class DeviceCtrlController {


    @Autowired
    private DeviceCtrlService deviceCtrlService;


    /**
     * 【服务调用】- 触发设备执行某个服务动作
     *
     * 请求示例：POST /eiot/device/service/invoke
     * {
     *   "deviceId": 123,
     *   "service": "restart",
     *   "args": {"delay": 5}
     * }
     *
     * 服务调用与属性设置的区别：
     *  - 服务调用：触发设备执行动作（如重启、校准），是"行为"
     *  - 属性设置：修改设备的状态值（如温度、开关），是"状态变更"
     *
     * @param request 包含 deviceId（设备ID）、service（服务名）、args（参数）
     */
    /**
     * 【服务调用】- 触发设备执行某个服务动作
     *
     * <p>这是下行控制最常用的接口。前端/小程序通过此接口下发控制指令给设备。</p>
     *
     * <h3>请求示例</h3>
     * <pre>
     * POST /eiot/device/service/invoke
     * {
     *   "deviceId": 2075459557809926144,   // 设备在 eiot_device_info 的主键ID
     *   "service": "switch_control",        // 服务名称，对应物模型中定义的 service identifier
     *   "args": { "raw_": "RELAY1_ON" }     // 服务参数，GE100D 透传时用 raw_ 前缀的 key
     * }
     * </pre>
     *
     * <h3>完整调用链</h3>
     * <pre>
     * 本方法 → deviceCtrlService.invokeService(deviceId, service, args)
     *   → DeviceCtrlServiceImpl.send() 构造 ThingModelMessage
     *     → ComponentManager.sendToDevice() Redis 路由查找
     *       → MQ 发布到 ge100d_device_thing topic
     *         → AbstractComponent.handler() 路由匹配
     *           → ThingComponent.onMessage() type="service" 分发
     *             → Ge100dComponent.serviceInvoke() 提取 raw_ 命令
     *               → Ge100dVerticle.publishCommand() MQTT 下发
     *                 → GE100D 硬件收到 → 执行继电器动作
     * </pre>
     *
     * @param request 包含 deviceId（设备ID）、service（服务名）、args（参数）
     */
    @Operation(summary = "服务调用")
    @PreAuthorize("@ss.hasPermission('iot:device:ctrl')") // 权限校验：需要 iot:device:ctrl 权限
    @PostMapping("/service/invoke")
    public CommonResult<String> invokeService(@RequestBody @Validated ServiceInvokeReqVo request) {
        // 调用业务层：deviceId → 查设备信息, service → 消息的 identifier, args → 消息的 data
        deviceCtrlService.invokeService(request.getDeviceId(), request.getService(), request.getArgs());
        return success(""); // 异步下发，立即返回成功。设备是否执行由设备侧决定
    }

    /**
     * 【属性获取】- 请求设备上报指定属性的当前值
     *
     * 与属性设置不同，此操作为"读"操作，不修改设备状态。
     * 设备收到请求后，应读取传感器数据并通过 PropertyReport 回复。
     *
     * 例如：读取设备的温度、湿度属性 → getProperty(deviceId, ["temperature","humidity"], true)
     *
     * @param request 包含 deviceId 和 propertyNames（要获取的属性名列表）
     */
    @Operation(summary = "属性获取")
    @PreAuthorize("@ss.hasPermission('iot:device:ctrl')")
    @PostMapping("/service/property/get")
    public CommonResult<String> invokeServicePropertySet(@RequestBody @Validated GetDeviceServicePropertyBo request) {
        deviceCtrlService.getProperty(request.getDeviceId(), request.getPropertyNames(), true);
        return success("");
    }

    /**
     * 【属性设置】- 修改设备的属性值（最常用的控制指令）
     *
     * 请求示例：POST /eiot/device/service/property/set
     * {
     *   "deviceId": 123,
     *   "args": {"power_switch": true, "brightness": 80}
     * }
     *
     * 属性设置是异步操作：平台下发指令后立即返回成功，
     * 设备收到指令后执行，并通过属性上报（PropertyReport）反馈实际状态。
     *
     * @param request 包含 deviceId 和 args（属性键值对 Map）
     */
    @Operation(summary = "属性设置")
    @PreAuthorize("@ss.hasPermission('iot:device:ctrl')")
    @PostMapping("/service/property/set")
    public CommonResult<String> setProperty(@RequestBody @Validated SetDeviceServicePropertyBo request) {
        deviceCtrlService.setProperty(request.getDeviceId(), request.getArgs());
        return success("");
    }

    /**
     * 【设备配置下发】- 将配置参数下发给设备
     *
     * 从数据库读取设备的配置信息，通过消息队列下发给设备。
     * 典型场景：修改设备上报频率、报警阈值等参数。
     *
     * 配置下发流程：
     *  1. 查询设备配置（DeviceConfigService.findByDeviceId）
     *  2. 构造 ThingModelMessage（type="config", identifier="set"）
     *  3. 通过消息队列路由到设备
     *
     * @param reqVo 包含 deviceId
     */
    @Operation(summary = "设备配置下发")
    @PreAuthorize("@ss.hasPermission('iot:device:ctrl')")
    @PostMapping("/config/send")
    public CommonResult<String> sendConfig(@Validated @RequestBody DeviceIdReqVo reqVo) {
        deviceCtrlService.sendConfig(reqVo.getDeviceId());
        return success("");
    }


//    @Operation(summary = "模拟设备上报")
//    @PreAuthorize("@ss.hasPermission('iot:device:ctrl')")
//    @PostMapping("/simulateSend")
//    public boolean simulateSend(
//            @Validated @RequestBody ThingModelMessageBo bo) {
//        ThingModelMessage message = bo.to(ThingModelMessage.class);
//        return deviceServiceImpl.simulateSend(message);
//    }

}
