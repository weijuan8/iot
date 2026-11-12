
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
package com.enjoyiot.module.eiot.api.component;

import com.enjoyiot.module.eiot.api.component.dto.ComponentInfo;
import com.enjoyiot.module.eiot.service.component.ComponentService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 【组件 API 实现类】
 *
 * ============================================================================
 * 【功能说明】
 * 这是组件查询自身配置的内部 API 实现。
 * 被 ThingComponent.config() 通过 ComponentServices.componentApi 调用，
 * 用于每 5 秒轮询组件的配置和启用状态。
 *
 * 【调用链】
 *  ThingComponent.config()（每 5 秒定时任务）
 *    → ComponentServices.getComponentApi().getInfo(getType())
 *      → ComponentApiImpl.getInfo(type)
 *        → ComponentService.getComponent(type)
 *          → ComponentMapper.selectOne(type)
 *            → 从 eiot_component 表读取
 *              → 返回 ComponentInfo{name, config, status}
 *
 * 【为什么需要这个 API？】
 *  组件运行在独立的模块中（如 iot-component-mqtt），
 *  而数据库操作在 biz 模块中。
 *  通过 ComponentApi 接口实现模块间解耦。
 */
@Component
public class ComponentApiImpl implements ComponentApi {

    @Resource
    private ComponentService componentService;


    @Override
    public ComponentInfo getInfo(String type) {
        return componentService.getComponent(type);
    }

}
