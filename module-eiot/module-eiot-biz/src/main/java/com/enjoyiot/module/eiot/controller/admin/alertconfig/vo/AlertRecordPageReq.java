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
package com.enjoyiot.module.eiot.controller.admin.alertconfig.vo;


import com.enjoyiot.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 告警消息分页查询请求
 */
@Schema(description = "告警消息分页查询请求")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRecordPageReq extends PageParam {

    @Schema(description = "告警ID")
    private Long id;

    /**
     * 告警名称（模糊搜索）
     */
    @Schema(description = "告警名称（模糊搜索）")
    private String name;

    /**
     * 告警严重度（1-5）
     */
    @Schema(description = "告警等级")
    private String level;

    /**
     * 是否已读
     */
    @Schema(description = "是否已读")
    private Boolean readFlg;

    // ========== 筛选条件 ==========

    /**
     * 租户ID（平台管理员可按租户筛选）
     */
    @Schema(description = "租户ID")
    private Long tenantId;

    /**
     * 养殖场ID
     */
    @Schema(description = "养殖场ID")
    private Long farmId;

    /**
     * 塘口ID
     */
    @Schema(description = "塘口ID")
    private Long pondId;

    /**
     * 模块ID
     */
    @Schema(description = "模块ID")
    private Long moduleId;

}
