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

package com.enjoyiot.eiot.component.core.model.up;

import com.enjoyiot.eiot.component.core.model.ActionType;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * 【服务回复】- 设备响应平台服务调用的返回结果
 *
 * 当平台通过 ServiceInvoke 调用设备服务（如重启、校准）后，
 * 设备执行完毕通过此消息返回执行结果。
 *
 * 【请求-响应关联】
 *  平台调用：ServiceInvoke.id = "req_001"
 *  设备回复：ServiceReply.replyId = "req_001"  ← 通过 replyId 关联原始请求
 *
 * 【处理链路】
 *  ThingComponent.report() 转换为 ThingModelMessage：
 *    - id = replyId（关联原始请求）
 *    - identifier = name + "_reply"（如 "restart_reply"）
 *    - type = "service"
 *    - data = params（执行结果）
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@ToString(callSuper = true)
public class ServiceReply extends ReportAction {

    /**
     * 服务名称
     * 对应物模型中定义的服务标识符，如 "restart"、"calibrate"
     */
    private String name;

    /**
     * 关联的请求 ID
     * 与原始 ServiceInvoke 的 id 对应，用于请求-响应链路追踪
     */
    private String replyId;

    /**
     * 执行状态码
     * 0 = 执行成功，非 0 = 执行失败（错误码由设备端定义）
     */
    private int code;

    /**
     * 服务执行返回的参数
     * 例如重启服务可能返回 {"boot_time": 1234} 表示启动耗时
     */
    private Map<String, Object> params;

    @Override
    public ActionType getType() {
        return ActionType.SERVICE_REPLY;
    }
}
