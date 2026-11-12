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

package com.enjoyiot.eiot.component.emqx.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备信息模型
 *
 * 【用途说明】
 * 本类用于在 EMQX 组件内部传递设备标识信息。
 * 主要在以下场景使用：
 * 1. EmqxVerticle.getDevice() 方法：从 MQTT Topic 中解析出 productKey 和 deviceName，构建 ThingDevice 对象
 * 2. 子设备注册流程：通过 ThingDevice 传递父设备的 deviceId 信息
 *
 * 【Topic 与 ThingDevice 的映射关系】
 * MQTT Topic 格式：/sys/{productKey}/{deviceName}/s/{method}
 *                     ↑ topicParts[2]  ↑ topicParts[3]
 * 例如：/sys/a1B2C3/device001/s/thing.event.property.post
 *       → productKey = "a1B2C3", deviceName = "device001"
 *
 * 【设备 ID 的层级关系】
 * - deviceId:  设备在平台数据库中的唯一标识（Long 型数字ID）
 * - productKey + deviceName: 设备的业务标识，在 Topic 中用于路由消息
 * - model:     设备型号，用于子设备注册时指定设备类型
 *
 * @author sjg
 * @see com.enjoyiot.eiot.component.emqx.service.EmqxVerticle#getDevice(String)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThingDevice {

    /**
     * 设备在平台中的唯一 ID（数据库主键）
     * 子设备注册时需要此 ID 作为 parentId
     */
    private String deviceId;

    /**
     * 产品 Key
     * 对应物联网平台中「产品」的唯一标识，一组同类设备共享同一个 productKey
     */
    private String productKey;

    /**
     * 设备名称（Device Name，简称 dn）
     * 同一产品下的设备唯一标识，与 productKey 组合可以唯一确定一个设备
     */
    private String deviceName;

    /**
     * 设备型号
     * 用于子设备注册时指定设备的物模型类型
     */
    private String model;

    /**
     * 设备密钥
     * 用于设备认证时的密钥校验
     */
    private String secret;
}
