package com.enjoyiot.eiot.component.core.model;
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


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

/**
 * 【设备行为抽象基类】- 所有 IoT 上下行消息的数据模型基类
 *
 * 该类实现了 IDeviceAction 接口，为所有具体的 Action 提供了基础字段实现：
 *  - id：行为唯一标识，用于请求-响应链路追踪
 *  - type：行为类型（ActionType 枚举），决定消息路由方向
 *  - productKey + deviceName：设备唯一标识（pk + dn 组合标识一个设备）
 *  - time：行为发生的时间戳
 *
 * 【序列化支持】
 *  实现 Serializable 接口，支持跨网络传输（如通过消息队列 Kafka/Redis 传递）。
 *
 * 【Lombok 注解说明】
 *  - @SuperBuilder：支持继承链的 Builder 模式，子类可直接使用 builder 构造
 *  - @ToString(callSuper = true)：toString 时包含父类字段
 *
 * 【继承结构】
 *  下行消息类（down 包）直接继承 AbstractAction
 *  上行消息类（up 包）通过 ReportAction 间接继承
 *
 * @author sjg
 */
@Data
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public abstract class AbstractAction implements IDeviceAction, Serializable {

    /**
     * 行为唯一标识 ID
     * 用于关联请求与响应，例如服务调用时 request.id → reply.replyId
     */
    protected String id;

    /**
     * 行为类型
     * 见 ActionType 枚举：AUTH/REGISTER/PING/PROPERTY_REPORT/EVENT_REPORT/SERVICE_INVOKE 等共 18 种
     */
    protected ActionType type;

    /**
     * 产品 Key（ProductKey）
     * IoT 平台中产品（设备型号）的唯一标识
     */
    protected String productKey;

    /**
     * 设备名称（DeviceName）
     * 与 productKey 组合形成设备全局唯一标识
     */
    protected String deviceName;

    /**
     * 行为发生时间戳（毫秒）
     * 用于时序存储和消息排序
     */
    protected Long time;

    public AbstractAction() {
    }

    /**
     * 通过类型构造，子类通常在构造函数中传入固定的 ActionType
     */
    public AbstractAction(ActionType type) {
        this.type = type;
    }


    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public ActionType getType() {
        return type;
    }

    @Override
    public void setType(ActionType type) {
        this.type = type;
    }

    @Override
    public String getProductKey() {
        return productKey;
    }

    @Override
    public void setProductKey(String productKey) {
        this.productKey = productKey;
    }

    @Override
    public String getDeviceName() {
        return deviceName;
    }

    @Override
    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    @Override
    public Long getTime() {
        return time;
    }

    @Override
    public void setTime(Long time) {
        this.time = time;
    }
}
