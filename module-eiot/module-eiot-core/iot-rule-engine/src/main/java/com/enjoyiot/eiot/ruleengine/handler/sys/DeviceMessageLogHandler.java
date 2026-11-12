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


import com.enjoyiot.eiot.IThingModelMessageData;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.handler.DeviceMessageHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 【设备消息日志处理器】- 将所有设备消息写入时序数据库
 *
 * 这是 Handler 链中的第一个处理器（@Order 值最小），负责将所有经过 device_thing topic 的
 * 消息原样存入 TDengine 时序数据库的 thing_model_message 表。
 *
 * 【作用】
 *  - 提供完整的消息历史记录，支持按时间范围查询
 *  - 为调试和问题排查提供数据依据
 *  - 为数据分析和报表提供原始数据源
 *
 * 【执行顺序】第 1 位（最高优先级）
 *  必须先于其他 handler 执行，确保即使后续处理出错，原始消息也已安全持久化。
 *
 * @author sjg
 */
@Slf4j
@Component
public class DeviceMessageLogHandler implements DeviceMessageHandler {

    /**
     * 物模型消息数据存储接口 —— 将消息写入 TDengine 时序数据库
     *
     * 实现类：ThingModelMessageDataImpl（TDengine REST API 实现）
     * 存储表：thing_model_message（全局超级表）
     * 子表命名：thing_model_message_{deviceId}（每个设备一张子表）
     *
     * 写入方式：INSERT INTO thing_model_message_{deviceId}
     *          USING thing_model_message TAGS ('{deviceId}')
     *          VALUES (time, mid, product_key, device_name, uid, type, identifier, code, data, report_time);
     *
     * @Lazy 延迟加载，避免与 TDengine 数据源初始化形成循环依赖
     */
    @Lazy
    @Autowired
    private IThingModelMessageData thingModelMessageData;

    /**
     * 【消息日志处理】- 将所有设备消息原样写入 TDengine thing_model_message 时序表
     *
     * 这是 Handler 链中的第 1 个处理器（@Order 值最小，最高优先级）。
     * 必须在所有其他 handler 之前执行，确保即使后续处理出错，原始消息也已安全持久化。
     *
     * 不做任何过滤：属性上报、事件、服务调用、状态变更等所有消息类型都会被存储。
     * 作用：消息追踪、调试、审计、数据回溯。
     *
     * @param msg 物模型消息（所有经过 device_thing topic 的消息）
     */
    @Override
    public void handle(ThingModelMessage msg) {
        // 将完整的物模型消息原样存入 TDengine 时序数据库
        // 底层通过 TDengine REST API 执行 INSERT 语句
        // 如果子表 thing_model_message_{deviceId} 不存在，TDengine 自动以超级表为模板创建
        // data 字段存储完整的 JSON 文本（通过 JsonUtils.toJsonString() 序列化）
        thingModelMessageData.add(msg);
    }

}
