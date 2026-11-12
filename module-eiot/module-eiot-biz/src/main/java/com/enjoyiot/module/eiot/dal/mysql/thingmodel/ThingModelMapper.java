
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
package com.enjoyiot.module.eiot.dal.mysql.thingmodel;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.module.eiot.dal.dataobject.thingmodel.ThingModelDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 产品物模型 Mapper
 *
 * @author EnjoyIot
 */
@Mapper
public interface ThingModelMapper extends BaseMapperX<ThingModelDO> {

    /**
     * 按塘口ID查询其下所有设备的产品物模型（一条 SQL 直接出结果）
     *
     * <p>链路：y_module_device.pond_id → eiot_device_info.product_key → eiot_thing_model.model
     * 子查询先去重 product_key，避免对 model 大字段做 DISTINCT。
     *
     * @param pondId 塘口ID
     * @return 该塘口下涉及的物模型（productKey + model JSON）
     */
    @Select("SELECT tm.product_key, tm.model " +
            "FROM eiot_thing_model tm " +
            "WHERE tm.deleted = 0 " +
            "  AND tm.product_key IN (" +
            "    SELECT DISTINCT d.product_key " +
            "    FROM eiot_device_info d " +
            "    INNER JOIN y_module_device md ON md.device_id = d.id " +
            "    WHERE md.pond_id = #{pondId} AND d.deleted = 0" +
            "  )")
    List<ThingModelDO> selectByPondId(@Param("pondId") Long pondId);

}
