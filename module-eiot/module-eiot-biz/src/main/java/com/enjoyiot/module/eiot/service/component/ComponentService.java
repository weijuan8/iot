
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
package com.enjoyiot.module.eiot.service.component;

import com.enjoyiot.module.eiot.api.component.dto.ComponentInfo;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentCreateReqVO;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentUpdateReqVO;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentPageReqVO;
import com.enjoyiot.module.eiot.dal.dataobject.component.ComponentDO;
import com.enjoyiot.framework.common.pojo.PageResult;

import jakarta.validation.Valid;

/**
 * 【组件配置 Service 接口】
 *
 * ============================================================================
 * 【数据表：eiot_component】
 *  此 Service 操作的是 eiot_component 表，该表存储组件的配置信息。
 *  注意：表中的数据不代表组件正在运行，只是配置记录。
 *  组件实际运行状态由组件实例自身的定时轮询 + stateChange 决定。
 *
 * 【表的生命周期】
 *  1. 组件启动 → 自动发现 → ComponentManager 调用 createComponent() → 入库（status=0）
 *  2. 管理员配置 → 调用 updateComponent() → 更新 config + status
 *  3. 组件轮询 → ThingComponent.config() → getComponent(type) → 获取最新配置
 *  4. 组件应用 → stateChange(enable, config) → 启停协议服务
 *
 * 【getComponent(type) 的调用方】
 *  - ThingComponent.config() 定时轮询（每 5 秒）
 *  - ComponentApiImpl.getInfo() → 组件查询自身配置
 */
public interface ComponentService {

    /**
     * 创建组件配置
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createComponent(@Valid ComponentCreateReqVO createReqVO);

    /**
     * 更新组件配置
     *
     * @param updateReqVO 更新信息
     */
    void updateComponent(@Valid ComponentUpdateReqVO updateReqVO);

    /**
     * 删除组件配置
     *
     * @param id 编号
     */
    void deleteComponent(Long id);

    /**
     * 获得组件配置
     *
     * @param id 编号
     * @return 组件配置
     */
    ComponentDO getComponent(Long id);

    /**
     * 获得组件配置分页
     *
     * @param pageReqVO 分页查询
     * @return 组件配置分页
     */
    PageResult<ComponentDO> getComponentPage(ComponentPageReqVO pageReqVO);

    /**
     * 按组件类型获取
     *
     * @param type 组件类型
     * @return 组件信息
     */
    ComponentInfo getComponent(String type);

}