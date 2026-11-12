
/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com | Tel: 19918996474
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot] | Tel: 19918996474
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
package com.enjoyiot.module.eiot.service.device;


import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;

import java.util.List;
import java.util.Map;

/**
 * 【设备控制服务接口】- 下行消息（平台 → 设备）的业务层抽象
 *
 * 该接口定义了所有平台到设备的控制操作，是下行消息链路的业务核心。
 * 所有方法都遵循统一的处理模式：
 *  1. 验证设备信息（getAndCheckDevice）
 *  2. 构造 ThingModelMessage（统一消息格式）
 *  3. 通过 send() 方法发布消息
 *     ├─ 虚拟设备 → VirtualManager.send()
 *     └─ 真实设备 → ComponentManager.sendToDevice()
 *
 * 【下行操作分类】
 *  ┌───────────────────┬──────────────────┬────────────────────────────┐
 *  │ 操作类型           │ ThingModelMessage │ 说明                       │
 *  │                   │ type/identifier   │                           │
 *  ├───────────────────┼──────────────────┼────────────────────────────┤
 *  │ invokeService()   │ service/{name}   │ 触发设备执行服务动作        │
 *  │ getProperty()     │ property/get     │ 请求设备上报属性值          │
 *  │ setProperty()     │ property/set     │ 修改设备属性值              │
 *  │ sendConfig()      │ config/set       │ 下发设备配置参数            │
 *  │ otaUpgrade()      │ ota/ota          │ 下发固件升级包              │
 *  │ bindDevice()      │ topo_change/change│ 通知网关子设备绑定关系     │
 *  │ unbindDevice()    │ topo_change/change│ 通知网关子设备解绑关系     │
 *  └───────────────────┴──────────────────┴────────────────────────────┘
 *
 * @Author: EnjoyIot
 * @Date: 2025/1/8 18:42
 * @Version: V1.0
 */
public interface DeviceCtrlService {

    /**
     * 【服务调用】- 触发设备执行指定服务
     *
     * @param deviceId 设备数据库 ID
     * @param service  服务名称（如 "restart"、"calibrate"）
     * @param args     服务参数（Key-Value 形式，如 {"delay": 5}）
     */
    void invokeService(Long deviceId, String service,
                         Map<String, Object> args);

    /**
     * 【服务调用（带权限检查）】
     * @param checkOwner true=需要校验设备归属权限
     */
    void invokeService(Long deviceId, String service,
                         Map<String, Object> args, boolean checkOwner);

    /**
     * 【OTA 固件升级】- 向设备下发固件升级包
     *
     * OTA 数据包含：下载地址(url)、版本号(version)、MD5校验值、签名等
     *
     * @param deviceId   设备 ID
     * @param checkOwner 是否校验权限
     * @param data       OTA 升级数据（OtaData 对象）
     */
    void otaUpgrade(Long deviceId, boolean checkOwner, Object data);

    /**
     * 【属性获取】- 请求设备上报指定属性的当前值
     *
     * @param deviceId   设备 ID
     * @param properties 要获取的属性标识符列表（如 ["temperature","humidity"]）
     * @param checkOwner 是否校验权限
     */
    void getProperty(Long deviceId, List<String> properties,
                       boolean checkOwner);

    /**
     * 【属性设置】- 修改设备属性值
     *
     * @param deviceId   设备 ID
     * @param properties 属性键值对（如 {"power_switch": true, "brightness": 80}）
     */
    void setProperty(Long deviceId, Map<String, Object> properties);

    /**
     * 【属性设置（带权限检查）】
     * @param checkOwner 是否校验设备归属权限
     */
    void setProperty(Long deviceId, Map<String, Object> properties,
                       boolean checkOwner);

    /**
     * 【配置下发（带权限检查）】
     * 从数据库读取设备配置，构造 ThingModelMessage 下发给设备
     *
     * @param deviceId   设备 ID
     * @param checkOwner 是否校验权限
     */
    void sendConfig(Long deviceId, boolean checkOwner);

    /**
     * 【配置下发】- 默认开启权限检查
     */
    void sendConfig(Long deviceId);

    /**
     * 【获取并校验设备】- 查询设备信息并验证权限和状态
     *
     * @param deviceId   设备 ID
     * @param checkOwner 是否校验权限
     * @return 设备信息对象
     */
    DeviceInfo getAndCheckDevice(Long deviceId, boolean checkOwner);

    /**
     * 【绑定子设备】- 将子设备绑定到网关，通知网关更新拓扑
     *
     * 流程：构造拓扑变更消息 → 下发给网关设备
     *
     * @param subDeviceIds 子设备 ID 列表
     * @param parentId     网关设备 ID
     */
    void bindDevice(List<Long> subDeviceIds, Long parentId);

    /**
     * 【解绑子设备】- 解除子设备与网关的绑定关系
     *
     * @param subDeviceIds 子设备 ID 列表
     */
    void unbindDevice(List<Long> subDeviceIds);
}
