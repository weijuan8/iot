
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

import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.api.component.dto.ComponentInfo;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentCreateReqVO;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentPageReqVO;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentUpdateReqVO;
import com.enjoyiot.module.eiot.convert.ComponentConvert;
import com.enjoyiot.module.eiot.dal.dataobject.component.ComponentDO;
import com.enjoyiot.module.eiot.dal.mysql.component.ComponentMapper;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;

import static com.enjoyiot.eiot.common.enums.ErrorCodeConstants.COMPONENT_NOT_EXISTS;
import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 【组件配置 Service 实现类】
 *
 * ============================================================================
 * 【操作的数据表】
 *  表名：eiot_component
 *  字段：id, name, type, config, status, remark, create_time, update_time
 *
 * 【调用链路】
 *  1. 组件自动发现入库：
 *     ComponentManager.componentDiscover() → createComponent()
 *
 *  2. 管理员配置/启停：
 *     ComponentController.update() → updateComponent()
 *
 *  3. 组件轮询配置：
 *     ThingComponent.config() → ComponentApi.getInfo() → getComponent(type)
 *
 * 【config 字段的格式】
 *  不同组件类型的 config 格式不同（JSON 字符串），由各组件自行解析：
 *  - MQTT：{"host":"127.0.0.1","port":1883,"ssl":false,...}
 *  - Modbus：{"port":502,"timer":10}
 *  - TCP：{"port":8080}
 *  - UDP：{"port":1234,"defaultProductKey":"prod_001","enableAutoRegister":true}
 *  - HTTP/CoAP：{"port":8080} / {"port":5683}
 */
@Service
@Validated
public class ComponentServiceImpl implements ComponentService {

    @Resource
    private ComponentMapper componentMapper;

    /**
     * 【创建组件】
     *
     * 调用方：
     *  - ComponentManager.componentDiscover()（自动发现时首次入库）
     *  - ComponentController.createComponent()（管理员手动创建）
     *
     * 创建的组件默认 status=0（停用），需要管理员手动配置并启用。
     */
    @Override
    public Long createComponent(ComponentCreateReqVO createReqVO) {
        // 插入
        ComponentDO component = ComponentConvert.INSTANCE.convert(createReqVO);
        componentMapper.insert(component);
        // 返回
        return component.getId();
    }

    /**
     * 【更新组件配置】
     *
     * 这是管理员操作组件的核心入口。
     * 更新后，组件在最多 5 秒内通过 ThingComponent.config() 感知变更。
     *
     * 【常见操作】
     *  - 修改配置参数：更新 config 字段（如 Broker 地址变更）
     *  - 启用组件：status 改为 1
     *  - 停用组件：status 改为 0
     *
     * 注意：更新操作不会直接通知组件，组件依赖定时轮询来感知变更。
     */
    @Override
    public void updateComponent(ComponentUpdateReqVO updateReqVO) {
        // 校验存在
        validateComponentExists(updateReqVO.getId());
        // 更新
        ComponentDO updateObj = ComponentConvert.INSTANCE.convert(updateReqVO);
        componentMapper.updateById(updateObj);
    }

    @Override
    public void deleteComponent(Long id) {
        // 校验存在
        validateComponentExists(id);
        // 删除
        componentMapper.deleteById(id);
    }

    private void validateComponentExists(Long id) {
        if (componentMapper.selectById(id) == null) {
            throw exception(COMPONENT_NOT_EXISTS);
        }
    }

    @Override
    public ComponentDO getComponent(Long id) {
        return componentMapper.selectById(id);
    }

    @Override
    public PageResult<ComponentDO> getComponentPage(ComponentPageReqVO pageReqVO) {
        return componentMapper.selectPage(pageReqVO);
    }

    /**
     * 【按组件类型获取配置】
     *
     * 这是 ThingComponent.config() 轮询配置的底层数据来源。
     * 查询条件：type 字段（如 "mqtt"、"emqx"、"tcp"）
     * 返回：ComponentInfo{name, config, status}
     *
     * 注意：每个 type 在数据库中只有一条记录，
     * 因为 type 是组件的"类标识"，一个类型可以有多个实例但共享同一份配置。
     */
    @Override
    public ComponentInfo getComponent(String type) {
        return ComponentConvert.INSTANCE.convertInfo(componentMapper.selectOne(ComponentDO::getType, type));
    }

}