
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
package com.enjoyiot.module.eiot.controller.admin.component;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.component.vo.*;
import com.enjoyiot.module.eiot.convert.ComponentConvert;
import com.enjoyiot.module.eiot.dal.dataobject.component.ComponentDO;
import com.enjoyiot.module.eiot.service.component.ComponentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

/**
 * 【管理后台 - 组件配置 Controller】
 *
 * ============================================================================
 * 【功能说明】
 * 这是后台管理系统中"组件管理"页面的后端 API，管理员通过此 API：
 *  1. 查看所有已发现的组件列表（分页查询）
 *  2. 为组件配置参数（如 MQTT Broker 地址、端口等）
 *  3. 启用/停用组件（通过 status 字段控制开关）
 *  4. 手动创建或删除组件
 *
 * ============================================================================
 * 【后台管理页面操作流程】
 *
 *  1. 组件自动发现：
 *     协议组件启动 → 自动发送 COMPONENT_DISCOVER_TOPIC
 *     → ComponentManager 自动入库（status=0 停用）
 *     → 管理员在后台"组件管理"页面看到新组件
 *
 *  2. 配置组件：
 *     管理员点击编辑 → 填写 config JSON（如 MQTT 连接参数）
 *     → 调用 PUT /update → 更新 eiot_component 表
 *
 *  3. 启用组件：
 *     管理员将 status 改为 1（启用）
 *     → 组件通过 config() 轮询感知变更
 *     → 调用 stateChange(true, config) → 启动协议服务
 *
 *  4. 停用组件：
 *     管理员将 status 改为 0（停用）
 *     → 组件通过 config() 轮询感知变更
 *     → 调用 stateChange(false, config) → 停止协议服务
 *
 * ============================================================================
 * 【API 列表】
 *  POST   /eiot/component/create  - 手动创建组件
 *  PUT    /eiot/component/update  - 更新组件（配置参数、启用状态等）
 *  DELETE /eiot/component/delete  - 删除组件
 *  GET    /eiot/component/get     - 获取单个组件详情
 *  GET    /eiot/component/page    - 分页查询组件列表
 *
 * ============================================================================
 * 【关键字段说明】
 *  type：组件类型标识（如 "mqtt"、"emqx"、"tcp"），与组件代码中的 getType() 对应
 *  config：JSON 配置字符串，每个组件类型的配置格式不同：
 *    - MQTT/EMQX：{"host":"127.0.0.1","port":1883,"ssl":false,...}
 *    - Modbus：{"port":502,"timer":10}
 *    - TCP：{"port":8080}
 *    - UDP：{"port":1234,"defaultProductKey":"prod_001","enableAutoRegister":true}
 *    - HTTP：{"port":8080}
 *    - CoAP：{"port":5683}
 *  status：0=停用（组件不工作），1=启用（组件正常运行）
 *
 * @see ComponentManager 组件自动发现注册
 * @see ThingComponent#config() 配置轮询（感知变更）
 */
@Tag(name = "管理后台 - 组件配置")
@RestController
@RequestMapping("/eiot/component")
@Validated
public class ComponentController {

    @Resource
    private ComponentService componentService;

    /**
     * 【手动创建组件】
     * 通常组件通过自动发现机制入库，此接口用于手动添加自定义组件。
     * 创建后 status 默认 0（停用），需管理员手动配置并启用。
     */
    @PostMapping("/create")
    @Operation(summary = "创建组件")
    @PreAuthorize("@ss.hasPermission('eiot:component:create')")
    public CommonResult<Long> createComponent(@Valid @RequestBody ComponentCreateReqVO createReqVO) {
        return success(componentService.createComponent(createReqVO));
    }

    /**
     * 【更新组件配置】
     *
     * 这是最核心的后台操作，管理员通过此接口：
     *  - 修改组件配置参数（如 Broker 地址、端口）
     *  - 切换组件启用/停用状态（status 字段）
     *
     * 更新后，组件会在最多 5 秒内通过 ThingComponent.config() 感知到变更，
     * 并调用 stateChange() 应用新配置。不需要重启系统！
     *
     * 【常见操作示例】
     *  - 启用 MQTT 组件：PUT /update {id:1, status:1, config:'{"port":1883}'}
     *  - 修改 EMQX 地址：PUT /update {id:2, status:1, config:'{"host":"10.0.0.1","port":1883}'}
     *  - 停用 Modbus 组件：PUT /update {id:3, status:0}
     */
    @PutMapping("/update")
    @Operation(summary = "更新组件")
    @PreAuthorize("@ss.hasPermission('eiot:component:update')")
    public CommonResult<Boolean> updateComponent(@Valid @RequestBody ComponentUpdateReqVO updateReqVO) {
        componentService.updateComponent(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除组件")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('eiot:component:delete')")
    public CommonResult<Boolean> deleteComponent(@RequestParam("id") Long id) {
        componentService.deleteComponent(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得组件")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('eiot:component:query')")
    public CommonResult<ComponentRespVO> getComponent(@RequestParam("id") Long id) {
        ComponentDO component = componentService.getComponent(id);
        return success(ComponentConvert.INSTANCE.convert(component));
    }

    /**
     * 【组件列表分页查询】
     * 后台管理页面"组件管理"的列表数据来源。
     * 返回所有已发现的组件，包含：
     *  - type：组件类型（mqtt/emqx/tcp/coap/http/udp/modbus-custom）
     *  - name：组件名称（如 "内置官方mqtt协议组件"）
     *  - status：启用状态（0=停用，1=启用）
     *  - config：当前配置
     *  - createTime：首次发现时间
     */
    @GetMapping("/page")
    @Operation(summary = "获得组件配置分页")
    @PreAuthorize("@ss.hasPermission('eiot:component:query')")
    public CommonResult<PageResult<ComponentRespVO>> getComponentPage(@Valid ComponentPageReqVO pageVO) {
        PageResult<ComponentDO> pageResult = componentService.getComponentPage(pageVO);
        return success(ComponentConvert.INSTANCE.convertPage(pageResult));
    }

}
