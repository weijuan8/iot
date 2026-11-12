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
package com.enjoyiot.module.eiot.api.module;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 模块信息（API 层 DTO，供组件调用，避免直接暴露 DO）
 *
 * @author EnjoyIot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YModuleInfo implements Serializable {

    /** 模块 ID */
    private Long id;

    /** 模块编码（业务侧用于 topic 携带） */
    private String moduleCode;

    /** 模块名称 */
    private String name;

    /** 所属养殖场 ID */
    private Long farmId;

    /** 所属塘口 ID */
    private Long pondId;

    /** 归属租户 ID（冗余自 y_module.tenant_id，绑定 y_module_device 时使用） */
    private Long tenantId;
}