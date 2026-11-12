
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
package com.enjoyiot.module.eiot.dal.dataobject.component;

import com.enjoyiot.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 【组件配置 DO】- 对应数据库表 eiot_component
 *
 * ============================================================================
 * 【表结构】
 *   CREATE TABLE eiot_component (
 *     id          BIGINT PRIMARY KEY,      -- 组件编号（自增）
 *     name        VARCHAR,                  -- 组件名称（如 "内置官方mqtt协议组件"）
 *     type        VARCHAR UNIQUE,           -- 组件类型（如 "mqtt"、"emqx"、"tcp"）
 *     config      TEXT,                     -- 配置 JSON（如 {"port":1883}）
 *     status      INT DEFAULT 0,            -- 状态：0=停用, 1=启用
 *     remark      VARCHAR,                  -- 备注
 *     create_time DATETIME,                 -- 创建时间（首次发现时间）
 *     update_time DATETIME                  -- 更新时间
 *   );
 *
 * 【数据来源】
 *  - 自动发现：ComponentManager.componentDiscover() → createComponent() → insert
 *  - 手动创建：ComponentController.create() → createComponent() → insert
 *
 * 【配置变更流程】
 *  管理员修改 status/config → updateComponent() → update
 *  → ThingComponent.config() 轮询感知 → stateChange()
 *
 * 【重要】type 字段是组件唯一标识
 *  - 每个组件类型在表中只有一条记录
 *  - type 值必须与组件代码中的 getType() 返回值一致
 *  - 例如：MqttComponent.getType() 返回 "mqtt"，数据库中 type='mqtt' 的记录即为该组件的配置
 */
@TableName("eiot_component")
@KeySequence("eiot_component_seq") 
@Data
@EqualsAndHashCode(callSuper = true)
public class ComponentDO extends BaseDO {

    /**
     * 组件编号
     */
    @TableId
    private Long id;

    /**
     * 组件名称（用于后台显示）
     */
    private String name;

    /**
     * 组件类型（唯一标识，如 "mqtt"、"emqx"、"tcp"、"coap"、"http"、"udp"、"modbus-custom"）
     */
    private String type;

    /**
     * 组件配置（JSON格式）
     * 不同组件类型的配置结构不同，由各组件自行解析：
     *   MQTT/EMQX: {"host":"...","port":1883,"ssl":false,...}
     *   Modbus:     {"port":502,"timer":10}
     *   TCP/UDP:    {"port":8080}
     */
    private String config;

    /**
     * 状态：0=停用, 1=启用
     *
     * 自动发现的组件默认 status=0（停用），需管理员手动启用。
     * 组件通过 ThingComponent.config() 感知 status 变化并启停服务。
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

}