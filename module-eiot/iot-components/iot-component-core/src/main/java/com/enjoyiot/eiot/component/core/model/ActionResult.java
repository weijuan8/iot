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

package com.enjoyiot.eiot.component.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 【动作执行结果】- 设备行为（Action）执行后的返回结果
 *
 * 当平台下发指令给设备后，设备执行完毕会返回此结果对象。
 * 用于表示指令执行是否成功以及失败原因。
 *
 * 【使用场景】
 *  例如服务调用（ServiceInvoke）、属性设置（PropertySet）、OTA 升级等，
 *  设备执行完毕后通过 ServiceReply 携带此结果返回给平台。
 *
 * 【字段说明】
 *  - code：状态码，0 表示成功，非 0 表示失败（具体错误码由业务定义）
 *  - reason：失败原因描述（code=0 时可为空）
 *
 * @author sjg
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionResult implements Serializable {

    /**
     * 状态码
     * 0 表示执行成功，非 0 表示执行失败（具体错误码由业务层定义）
     */
    private int code;

    /**
     * 失败原因描述
     * 当 code != 0 时，说明具体失败原因，便于排查问题
     */
    private String reason;

}
