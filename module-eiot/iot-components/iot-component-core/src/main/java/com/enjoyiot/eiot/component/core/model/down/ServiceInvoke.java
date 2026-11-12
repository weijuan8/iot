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

package com.enjoyiot.eiot.component.core.model.down;


import com.enjoyiot.eiot.component.core.model.AbstractAction;
import com.enjoyiot.eiot.component.core.model.ActionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * 【服务调用】- 平台调用设备能力/执行设备动作（下行消息）
 *
 * 服务调用与属性设置的区别：
 *  - 属性设置（PropertySet）：修改设备的某个属性值（状态变更）
 *  - 服务调用（ServiceInvoke）：触发设备执行某个动作（行为调用）
 *
 * 【典型场景】
 *  - 设备重启：name="restart", params={}
 *  - 固件校准：name="calibrate", params={"sensor": "temperature"}
 *  - 设备自检：name="self_check", params={"mode": "full"}
 *  - 远程锁门：name="lock", params={"timeout": 30}
 *
 * 【请求-响应模式】
 *  ServiceInvoke 是异步的请求-响应模式：
 *  1. 平台发送 ServiceInvoke（id="req_001"）
 *  2. 设备执行服务
 *  3. 设备通过 ServiceReply（replyId="req_001"）返回结果
 *  4. 通过 replyId 关联原始请求
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ServiceInvoke extends AbstractAction {

    /**
     * 服务名称
     * 对应物模型中定义的服务标识符，如 "restart"、"calibrate"、"self_check"
     */
    private String name;

    /**
     * 服务调用参数
     * 服务所需的输入参数，Key 为参数名，Value 为参数值
     */
    private Map<String, ?> params;

    @Override
    public ActionType getType() {
        return ActionType.SERVICE_INVOKE;
    }
}
