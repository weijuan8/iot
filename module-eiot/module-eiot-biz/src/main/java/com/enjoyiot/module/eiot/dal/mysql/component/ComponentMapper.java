
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
package com.enjoyiot.module.eiot.dal.mysql.component;

import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.module.eiot.controller.admin.component.vo.ComponentPageReqVO;
import com.enjoyiot.module.eiot.dal.dataobject.component.ComponentDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 【组件配置 Mapper】
 *
 * 操作表：eiot_component
 *
 * 【查询场景】
 *  - selectByName：按名称查询（组件发现时去重检查）
 *  - selectPage：后台管理列表（支持按名称、类型、状态筛选）
 *  - selectOne(type)：组件轮询配置时使用（ThingComponent.config() → getComponent(type)）
 */
@Mapper
public interface ComponentMapper extends BaseMapperX<ComponentDO> {

    default ComponentDO selectByName(String name) {
        return selectOne(new LambdaQueryWrapperX<ComponentDO>()
                .eq(ComponentDO::getName, name));
    }

    /**
     * 【分页查询组件列表】
     * 后台管理页面"组件管理"的数据源。
     * 支持按名称（模糊）、类型、状态筛选。
     */
    default PageResult<ComponentDO> selectPage(ComponentPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ComponentDO>()
                .likeIfPresent(ComponentDO::getName, reqVO.getName())
                .eqIfPresent(ComponentDO::getType, reqVO.getType())
                .eqIfPresent(ComponentDO::getStatus, reqVO.getStatus())
                .orderByDesc(ComponentDO::getId));
    }

}