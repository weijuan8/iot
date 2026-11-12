
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
package com.enjoyiot.module.eiot.api.component.dto;

import lombok.Data;

/**
 * 【组件信息 DTO】
 *
 * ============================================================================
 * 【字段说明】
 *  name：组件名称（如 "内置官方mqtt协议组件"），仅用于后台显示
 *  config：组件配置 JSON 字符串，由各组件自行解析
 *         不同组件类型格式不同，例如：
 *           MQTT:  {"host":"127.0.0.1","port":1883,"ssl":false,...}
 *           Modbus: {"port":502,"timer":10}
 *           TCP:    {"port":8080}
 *  status：启用状态，0=停用（组件不工作），1=启用（组件正常运行）
 *
 * 【使用场景】
 *  通过 ComponentApi.getInfo(type) 获取，被 ThingComponent.config() 定时轮询使用。
 *  组件通过对比 status 和 config 是否变化来决定是否重启服务。
 */
@Data
public class ComponentInfo {

    /**
     * 组件名称
     */
    private String name;

    /**
     * 组件配置（JSON 字符串）
     */
    private String config;

    /**
     * 启用状态：0=停用, 1=启用
     */
    private Integer status;

}
