
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
package com.enjoyiot.module.eiot.api.device;

import com.enjoyiot.eiot.common.thing.ThingService;
import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.module.eiot.api.device.dto.*;

import java.util.List;
import java.util.Map;

public interface DeviceApi {

    DeviceInfo getDeviceByPkDnByCache(String pk, String dn);

    DeviceInfo getDeviceInfoFromCache(Long deviceId);

    DeviceInfo registerDevice(RegisterDevice registerDevice);

    CommonResult<DeviceInfo> auth(DeviceAuth deviceAuth);

    Map<String, DevicePropertyCache> getPropertiesFromCache(Long deviceId);

    void updateDeviceLastTimeCache(Long deviceId, long lastTime);

    Boolean updateDeviceState(Long deviceId, boolean online);

    void savePropertiesCache(Long deviceId, Map<String, DevicePropertyCache> properties);

    void clearPropertiesCache(String productKey);

    DeviceConfig getDeviceConfig(Long deviceId);

    DeviceConfig getDeviceConfig(String productKey, String dn);

    /**
     * 【调用设备服务】- 下行消息（平台→设备）的 API 层入口
     *
     * 这是模块间调用的标准入口，供规则引擎、定时任务等模块向设备下发指令。
     *
     * ThingService 对象包含：
     *  - productKey + dn：目标设备标识
     *  - type：消息类型（property/service/config 等）
     *  - identifier：操作标识符（"set"/"get"/服务名）
     *  - params：操作参数
     *
     * 【调用链路】
     *  DeviceApi.invoke()
     *    → DeviceApiImpl.invoke()
     *      → deviceCtrlService.invokeService()
     *        → 进入标准下行消息链路
     *
     * 注意：invoke 走的是 type="service"（服务调用）链路，
     * 属性设置（type="property", identifier="set"）请使用 {@link #setProperty(Long, Map)}。
     *
     * @param service ThingService 对象，封装了目标设备和操作信息
     */
    void invoke(ThingService<?> service);

    /**
     * 【属性设置】- 向设备下发属性设置指令（propertiesSet）
     *
     * 等价 deviceCtrlService.setProperty(deviceId, properties)，
     * 最终下发 type="property", identifier="set", data={key: value} 的标准属性设置消息。
     * 供规则引擎、联动任务等模块跨模块调用（rule-engine 不依赖 biz，需经此 API 桥接）。
     *
     * @param deviceId   设备 ID（eiot_device_info 主键）
     * @param properties 属性键值对（如 {"ge_aerator1": 1}）
     */
    void setProperty(Long deviceId, Map<String, Object> properties);

    List<DeviceInfo> getSubDevicesByProductKeAndDeviceName(String pk, String dn);

    /**
     * 按产品 Key 查询该产品下所有设备列表（全量，不走本地缓存）。
     * 用于定时轮询等需要全量设备信息的场景。
     *
     * @param productKey 产品 Key
     * @return 设备列表，无数据返回空列表
     */
    List<DeviceInfo> getDeviceListByProductKey(String productKey);

    Boolean deregisterSubDevice(String pk, String dn, String model, String subPkDeregister, String subDnDeregister);
}
