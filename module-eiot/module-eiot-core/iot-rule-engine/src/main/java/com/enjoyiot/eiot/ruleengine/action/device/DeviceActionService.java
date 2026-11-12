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
package com.enjoyiot.eiot.ruleengine.action.device;


import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.common.thing.ThingService;
import com.enjoyiot.eiot.common.utils.UniqueIdUtil;

import com.enjoyiot.module.eiot.api.device.DeviceApi;
import lombok.Data;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【设备动作服务】- 规则引擎中设备控制指令的构造与下发
 *
 * 该类是规则引擎与下行消息链路的"桥梁"，负责：
 * 1. 解析规则配置中的 Service 对象（设备标识 + 操作类型 + 参数）
 * 2. 构造 ThingService 统一对象
 * 3. 调用 DeviceApi.invoke() 进入标准下行链路
 *
 * 【device 字段格式】
 *  "{productKey}/{deviceName}" 例如 "prod_001/dev_001"
 *  通过 "/" 分割获取 pk 和 dn
 *
 * 【type 字段自动推断】
 *  如果 identifier 是 "set" 或 "get" → TYPE_PROPERTY（属性操作）
 *  其他 → TYPE_SERVICE（服务调用）
 *
 * 【完整调用链】
 *  DeviceAction.execute()
 *    → DeviceActionService.invoke()
 *      → DeviceApi.invoke(ThingService)
 *        → DeviceApiImpl.invoke()
 *          → DeviceCtrlServiceImpl.invokeService()
 *            → send() 构造 ThingModelMessage
 *              → ComponentManager.sendToDevice()
 *                → Redis 路由 → MQ 发布
 *                  → ThingComponent.onMessage()
 *                    → EmqxComponent/MqttComponent.publish()
 *                      → MQTT → 设备
 */
@Component
public class DeviceActionService {

    @Resource
    private DeviceApi deviceApi;

    /**
     * 【执行设备动作】- 构造 ThingService 并通过 DeviceApi 下发
     *
     * @param service 规则配置中的 Service 对象
     * @return 消息 ID（mid），用于追踪
     */
    public String invoke(Service service) {
        // 从 "pk/dn" 格式的 device 字段中解析出产品 Key 和设备名称
        String[] pkDn = service.getDevice().split("/");
        ThingService<Map<String, Object>> thingService = new ThingService<>();
        thingService.setMid(UniqueIdUtil.newRequestId());
        thingService.setProductKey(pkDn[0]);
        thingService.setDn(pkDn[1]);
        thingService.setType(service.getType());
        thingService.setIdentifier(service.getIdentifier());
        thingService.setParams(service.parseInputData());
        // 调用 DeviceApi.invoke() 进入标准下行消息链路
        deviceApi.invoke(thingService);
        return thingService.getMid();
    }

    /**
     * 【规则引擎 Service 配置】- 规则中配置的单个设备操作
     *
     * 例如一条规则可能配置："当温度>40°C时，向设备 prod_001/dev_001 设置 power_switch=false"
     * 这个配置就会被反序列化为一个 Service 对象。
     */
    @Data
    public static class Service {

        /** 目标设备标识，格式：{productKey}/{deviceName}，如 "prod_001/dev_001" */
        private String device;

        /** 操作标识符：服务名（如 "restart"）或属性操作类型（"set"/"get"） */
        private String identifier;

        /** 消息类型（自动推断：identifier 为 set/get 时 = property，否则 = service） */
        private String type;

        /**
         * 【自动推断消息类型】
         * identifier 为 "set" 或 "get" → TYPE_PROPERTY（属性操作）
         * 其他（如 "restart"、"calibrate"）→ TYPE_SERVICE（服务调用）
         */
        public String getType() {
            //identifier为set固定为属性设置，其它为服务调用
            if (ThingModelMessage.ID_PROPERTY_SET.equals(identifier) ||
                    ThingModelMessage.ID_PROPERTY_GET.equals(identifier)) {
                return ThingModelMessage.TYPE_PROPERTY;
            }
            return ThingModelMessage.TYPE_SERVICE;
        }

        /** 输入参数列表 */
        private List<Parameter> inputData;

        /**
         * 【解析输入参数】- 将 Parameter 列表转换为 Map
         * 例如 [{identifier:"power_switch", value:false}] → {"power_switch": false}
         */
        public Map<String, Object> parseInputData() {
            Map<String, Object> data = new HashMap<>();
            for (Parameter p : inputData) {
                data.put(p.getIdentifier(), p.getValue());
            }
            return data;
        }

        /**
         * 【参数定义】- 单个参数的标识符与值
         */
        @Data
        public static class Parameter {
            /** 参数标识符（如 "power_switch"、"delay"） */
            private String identifier;
            /** 参数值（支持 String/Number/Boolean 等） */
            private Object value;
        }
    }

}
