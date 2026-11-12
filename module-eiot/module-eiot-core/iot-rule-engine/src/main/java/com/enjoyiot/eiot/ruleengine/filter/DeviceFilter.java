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
package com.enjoyiot.eiot.ruleengine.filter;


import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 【设备过滤器】- 按设备当前状态进行条件判断
 *
 * 这是规则引擎中唯一实现的过滤器，通过查询设备当前状态来判断条件是否满足。
 *
 * 【支持的过滤维度】
 *  - property：设备属性值判断（如 temperature > 30）
 *    - 从 Redis 缓存查询设备当前属性值
 *    - 通过 PathValueResolver 支持嵌套路径（如 "data.inner.temp"）
 *  - state：设备在线状态判断（如 online == true）
 *  - tag：设备标签值判断（如 tag.location == "factory_a"）
 *
 * 【与 DeviceListener 中 DeviceCondition 的区别】
 *  - Listener 的 DeviceCondition：判断消息本身的内容（消息中的 data 字段）
 *  - Filter 的 DeviceCondition：   判断设备当前的状态（从 Redis/数据库查询）
 *    - 例如：消息上报 temperature=28，但设备当前缓存值是 32
 *      - Listener 判断的是"消息中的值 28"
 *      - Filter 判断的是"设备当前缓存值 32"
 *
 * 【执行流程】
 *  1. 遍历所有 DeviceCondition（AND 关系）
 *  2. 如果 condition 未指定 device，使用消息中的 deviceId
 *  3. 注入 deviceApi，调用 condition.matches() 查询设备状态并比较
 *  4. 所有 condition 都满足 → 返回 true
 */
@Data
public class DeviceFilter implements Filter<DeviceCondition> {

    /** 过滤器类型常量，注册到规则引擎时用于类型匹配 */
    public static String TYPE = "device";

    /** 类型标识（固定为 "device"），由 JSON 反序列化填充 */
    private String type;

    /** 产品 Key（ProductKey），限定过滤范围 */
    private String pk;

    /** 设备名称（DeviceName），"#" 表示该产品下所有设备 */
    private String dn;

    /** 过滤条件列表（AND 关系，全部满足才通过） */
    private List<DeviceCondition> conditions;

    /** 设备 API（由外部注入，用于查询设备当前属性值/状态/标签，底层走 Redis 缓存） */
    private DeviceApi deviceApi;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public void init() {
        // 暂无初始化逻辑，预留扩展点
    }

    /**
     * 执行过滤器逻辑，采用 AND 短路策略遍历所有 DeviceCondition。
     *
     * 遍历步骤：
     *  1. 克隆每个 condition（避免并发场景下修改原对象导致污染）
     *  2. 若 condition 未指定 device 或指定了通配 "null/#"，则使用当前消息的 deviceId 填充
     *  3. 注入 deviceApi，使 condition 能通过 API 查询设备当前状态
     *  4. 调用 condition.matches() 进行条件判断
     *  5. 任一 condition 返回 false → 短路返回 false（AND 逻辑）
     *  6. 所有 condition 通过 → 返回 true
     *
     * @param msg 物模型消息（设备上报的属性/事件/服务调用消息）
     * @return true=允许执行后续 Action, false=拦截
     */
    @Override
    public boolean execute(ThingModelMessage msg) {
        // 遍历所有条件
        for (DeviceCondition condition : getConditions()) {
            // 克隆条件对象，避免修改原对象（多个消息并发处理时防止 condition 互相干扰）
            DeviceCondition con = condition.clone();
            // 如果未指定 device 或使用了通配符 "null/#"，则使用消息中的 deviceId 作为目标设备
            if (StringUtils.isBlank(con.getDevice()) || "null/#".equals(con.getDevice())) {
                con.setDevice(String.valueOf(msg.getDeviceId()));
            }

            // 注入 deviceApi，使 condition 能通过 API 查询设备当前属性值/在线状态/标签
            con.setDeviceApi(deviceApi);
            // AND 关系：任一条件不满足即短路返回 false
            if (!con.matches()) {
                return false;
            }
        }
        // 所有条件都满足，过滤通过
        return true;
    }

}
