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
package com.enjoyiot.eiot.ruleengine.handler.sys;


import com.enjoyiot.eiot.IDevicePropertyData;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.handler.DeviceMessageHandler;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.DevicePropertyCache;
import com.enjoyiot.module.eiot.api.thingmodel.ThingModelApi;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【设备属性消息处理器】- 属性上报的物模型校验与持久化
 *
 * 这是 Handler 链中最重要的处理器之一，负责处理设备属性上报（type="property", identifier="report"）。
 * 它实现了完整的「物模型」校验机制，确保只有符合物模型定义的属性才能入库。
 *
 * 【处理流程】
 *  1. 过滤非属性上报消息（仅处理 type="property" && identifier="report"）
 *  2. 通过 DeviceApi 查询设备信息（获取 productKey）
 *  3. 通过 ThingModelApi 获取设备的物模型定义（属性白名单 + 数据类型定义）
 *  4. 遍历上报的属性 Map：
 *     - 白名单过滤：只处理物模型中已定义的属性标识
 *     - 类型归一化：通过 DataType.parse() 将属性值转为标准类型
 *     - 布尔值转换：Boolean → 1/0（时序数据库兼容）
 *     - 空值过滤：归一化后为 null 的属性跳过
 *  5. 更新 Redis 设备当前属性缓存（device:{deviceId}:properties）
 *  6. 写入 TDengine 时序数据库（device_property 超级表）
 *
 * 【物模型的作用】
 *  物模型定义了产品下所有属性的标识符和数据类型，DevicePropertyHandler 以此作为"白名单"：
 *  - 只有物模型中定义的属性才会被存储
 *  - 属性值按定义的类型进行归一化（如 "25.5" → 25.5）
 *  - 非物模型定义的字段会被自动丢弃
 *
 * 【执行顺序】第 2 位
 *  在日志入库之后执行，确保属性数据持久化前原始消息已安全存储。
 */
@Slf4j
@Component
public class DevicePropertyHandler implements DeviceMessageHandler {

    /** 设备属性数据存储接口（写入 TDengine 时序库） */
    @Resource
    private IDevicePropertyData devicePropertyData;

    /** 物模型 API（获取产品的物模型定义，用于属性白名单校验） */
    @Resource
    private ThingModelApi thingModelApi;

    /** 设备 API（查询设备信息和缓存） */
    @Resource
    private DeviceApi deviceApi;

    @Override
    public void handle(ThingModelMessage msg) {
        // 【过滤】仅处理属性上报消息
        if (!ThingModelMessage.TYPE_PROPERTY.equals(msg.getType())) {
            return;
        }
        if (!ThingModelMessage.ID_PROPERTY_REPORT.equals(msg.getIdentifier())) {
            return;
        }

        // data 必须是 Map 类型（属性键值对）
        if (!(msg.getData() instanceof Map)) {
            return;
        }

        Map<String, Object> properties = (Map<String, Object>) msg.getData();
        Long deviceId = msg.getDeviceId();
        // 查询设备信息（从 Redis 缓存获取）
        DeviceInfo deviceInfo = deviceApi.getDeviceInfoFromCache(deviceId);
        if (deviceInfo == null) {
            return;
        }

        // 【关键】获取物模型定义 —— 属性白名单 + 数据类型
        ThingModel thingModel = thingModelApi.getThingModelByProductKeyFromCache(deviceInfo.getProductKey());
        if (thingModel == null) {
            return;
        }

        // 构建物模型属性 Map：{属性标识 → DataType（包含类型和规范）}
        Map<String, ThingModel.DataType> thingModelProperties = thingModel.getModel().
                getProperties().stream().collect(Collectors.toMap(
                        ThingModel.Property::getIdentifier, ThingModel.Property::getDataType));

        Map<String, DevicePropertyCache> addProperties = new HashMap<>();
        Long occurred = msg.getOccurred();
        if (occurred == null) {
            occurred = System.currentTimeMillis();
        }

        // 【核心】遍历上报属性，进行白名单过滤 + 类型归一化
        Long finalOccurred = occurred;
        properties.forEach((key, val) -> {
            // 白名单过滤：只处理物模型中已定义的属性
            if (thingModelProperties.containsKey(key)) {
                ThingModel.DataType dataType = thingModelProperties.get(key);
                // 类型归一化：将原始值按物模型定义的类型进行转换
                Object normalizedVal = dataType == null ? val : dataType.parse(val);
                // 布尔值转换：Boolean → 1/0（时序数据库兼容）
                if (normalizedVal instanceof Boolean) {
                    normalizedVal = (Boolean) normalizedVal ? 1 : 0;
                }
                if (normalizedVal == null && val instanceof Boolean) {
                    normalizedVal = (Boolean) val ? 1 : 0;
                }
                // 空值跳过
                if (normalizedVal == null) {
                    return;
                }
                // 替换原值为归一化后的值
                properties.replace(key, normalizedVal);
                DevicePropertyCache propertyCache = new DevicePropertyCache();
                propertyCache.setValue(normalizedVal);
                propertyCache.setOccurred(finalOccurred);
                addProperties.put(key, propertyCache);
            }
        });

        if (addProperties.isEmpty()) {
            return;
        }

        // 【步骤1】更新 Redis 中设备的当前属性缓存（实时查询用）
        updateDeviceCurrentProperties(deviceId, addProperties);

        // 【步骤2】将属性值写入 TDengine 时序数据库（历史数据查询用）
        devicePropertyData.addProperties(deviceId, addProperties, occurred);
    }


//    private void handleLocate(DeviceInfo deviceInfo, Object data, ThingModel.DataType dataType) {
//        if (!"position".equals(dataType.getType())) {
//            return;
//        }
//        //如果是定位属性需要做一些处理
//        Object specs = dataType.getSpecs();
//        String locateType = "";
//        if (specs instanceof Map) {
//            Object objlocateType = ((Map<?, ?>) specs).get("locateType");
//            //定位方式
//            if (objlocateType != null) {
//                locateType = objlocateType.toString();
//            }
//            if (StringUtils.isBlank(locateType)) {
//                return;
//            }
//            if ("lonLat".equals(locateType)) {
//                //经纬度定位格式：经度,纬度
//                String[] lonLats = data.toString().split(",");
//                deviceInfo.getLocate().setLongitude(lonLats[0]);
//                deviceInfo.getLocate().setLatitude(lonLats[1]);
//                deviceInfoData.save(deviceInfo);
//            } else if ("basestation".equals(locateType)) {
//                //基站定位
//            } else if ("ipinfo".equals(locateType)) {
//                //ip定位
//            }
//        }
//    }

    /**
     * 【更新设备当前属性缓存】- 写入 Redis 缓存
     *
     * 设备属性有两层存储：
     *  - Redis 缓存（当前属性）：供实时查询，只保存最新值
     *    Key: device:{deviceId}:properties
     *    Value: {属性标识 → DevicePropertyCache{value, occurred}}
     *  - TDengine 时序库（历史属性）：供历史趋势查询，保存所有时间点的值
     *
     * @param deviceId   设备 ID
     * @param properties 经过物模型校验和归一化后的属性 Map
     */
    private void updateDeviceCurrentProperties(Long deviceId, Map<String, DevicePropertyCache> properties) {
        try {
            log.info("save device property,deviceId:{},property:{}", deviceId, JsonUtils.toJsonString(properties));
            deviceApi.savePropertiesCache(deviceId, properties);
        } catch (Throwable e) {
            log.error("save device current properties error", e);
        }
    }
}
