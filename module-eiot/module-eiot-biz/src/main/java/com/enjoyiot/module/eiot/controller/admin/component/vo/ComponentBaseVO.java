
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
package com.enjoyiot.module.eiot.controller.admin.component.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;

/**
 * 【组件配置 Base VO】
 *
 * ============================================================================
 * 【字段说明】
 *  name：组件显示名称，如 "内置官方mqtt协议组件"
 *  type：组件类型标识，如 "mqtt"、"emqx"、"tcp"
 *        ← 此值必须与组件代码中 getType() 返回值完全一致
 *  config：组件配置 JSON 字符串，格式因组件类型而异：
 *    - MQTT/EMQX: {"host":"127.0.0.1","port":1883,"ssl":false,...}
 *    - Modbus:     {"port":502,"timer":10}
 *    - TCP:        {"port":8080}
 *    - UDP:        {"port":1234,"defaultProductKey":"prod_001","enableAutoRegister":true}
 *    - HTTP:       {"port":8080}
 *    - CoAP:       {"port":5683}
 *  status：0=停用（组件不工作），1=启用（组件正常运行）
 *          ← 组件的"开关"，管理员通过此字段控制组件启停
 *  remark：备注信息
 *
 * 【status 的作用机制】
 *  1. 管理员在后台将 status 改为 1
 *  2. ThingComponent.config() 每 5 秒轮询数据库
 *  3. 检测到 status 变化 → 调用 stateChange(true, config)
 *  4. 组件解析配置 → 启动协议服务（如 MQTT Broker）
 *
 *  管理员将 status 改为 0：
 *  → stateChange(false, config) → 停止协议服务
 */
@Schema(description = "管理后台 - 组件配置 Base VO")
@Data
@SuperBuilder
@NoArgsConstructor
public class ComponentBaseVO {

    @Schema(description = "组件名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "测试组件")
    @NotNull(message = "组件名称不能为空")
    private String name;

    @Schema(description = "组件类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "mqtt")
    @NotNull(message = "组件类型不能为空")
    private String type;

    @Schema(description = "组件配置", requiredMode = Schema.RequiredMode.REQUIRED)
    private String config;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注", example = "我是备注")
    private String remark;

}