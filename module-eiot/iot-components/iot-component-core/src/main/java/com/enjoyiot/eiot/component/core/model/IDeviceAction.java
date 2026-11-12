/*
 *
 *  * | Licensed 未经许可不能去掉「EnjoyIot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2024] [EnjoyIot]
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

package com.enjoyiot.eiot.component.core.model;

/**
 * 【设备行为接口】- IoT 物模型交互的顶层契约
 *
 * 定义了一个设备"动作"（Action）所需的核心属性，所有上下行消息模型都实现了该接口。
 * 在物联网平台中，一次设备与平台的交互被视为一个"行为"，例如：
 *  - 上行（up）：属性上报、事件上报、设备注册、心跳等
 *  - 下行（down）：属性设置、服务调用、OTA 升级等
 *
 * 每个 Action 都携带以下核心信息：
 *  - id：行为唯一标识（用于追踪请求-响应链路）
 *  - type：行为类型（见 ActionType 枚举，共 18 种）
 *  - productKey + deviceName：标识行为属于哪个产品下的哪个设备
 *  - time：行为发生时间戳
 *
 * 【继承体系】
 *  IDeviceAction（接口）
 *    └── AbstractAction（抽象类，实现基础字段）
 *          ├── [down 子类] DeviceConfig、DeviceOta、PropertyGet、PropertySet、ServiceInvoke 等（平台下发）
 *          └── ReportAction（抽象类，标记上行行为）
 *                └── [up 子类] PropertyReport、EventReport、DeviceRegister 等（设备上报）
 *
 * @author sjg
 */
public interface IDeviceAction {

    /**
     * 获取行为唯一标识 ID
     * 用于关联请求和响应，例如服务调用的 requestId 会透传到 ServiceReply 的 replyId
     * @return 唯一标识字符串
     */
    String getId();

    /**
     * 设置行为 ID
     * @param id 唯一标识
     */
    void setId(String id);

    /**
     * 获取行为类型
     * 决定消息将如何被路由和处理（属性上报、事件上报、服务调用等）
     * @return ActionType 枚举值（共 18 种类型）
     */
    ActionType getType();

    /**
     * 设置行为类型
     * @param type ActionType 枚举
     */
    void setType(ActionType type);

    /**
     * 获取产品 Key（ProductKey）
     * ProductKey 是 IoT 平台中产品的唯一标识，一个产品下可以有多个设备
     * @return 产品 Key
     */
    String getProductKey();

    /**
     * 设置产品 Key
     * @param productKey 产品标识
     */
    void setProductKey(String productKey);

    /**
     * 获取设备名称（DeviceName，简称 DN）
     * 与 ProductKey 组合形成设备全局唯一标识：pk + dn
     * @return 设备名称
     */
    String getDeviceName();

    /**
     * 设置设备名称
     * @param deviceName 设备名称
     */
    void setDeviceName(String deviceName);

    /**
     * 获取行为发生的时间戳（毫秒）
     * 用于时序存储、消息排序、离线数据回放等场景
     * @return 毫秒时间戳
     */
    Long getTime();

    /**
     * 设置时间戳
     * @param time 毫秒时间戳
     */
    void setTime(Long time);
}
