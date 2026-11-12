
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
package com.enjoyiot.module.eiot.dal.mysql;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.module.eiot.dal.dataobject.AlertRecordDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 告警记录 Mapper
 *
 * @author EnjoyIot
 */
@Mapper
public interface AlertRecordMapper extends BaseMapperX<AlertRecordDO> {

    /**
     * 自定义分页查询，一条 SQL LEFT JOIN 查出关联名称。
     * Page 参数自动触发 MyBatis-Plus 分页插件添加 LIMIT/OFFSET。
     *
     * 注意：此方法配合 @TenantIgnore 使用，租户过滤由 Service 层手动控制（传入 tenantId 参数），
     * 以支持平台管理员跨租户查看告警。
     */
    @Select("<script>" +
            "SELECT r.*, f.farm_name AS farmName, p.pond_name AS pondName, d.name AS deviceName, m.module_name AS moduleName " +
            "FROM eiot_alert_record r " +
            "LEFT JOIN y_farm f ON f.id = r.farm_id " +
            "LEFT JOIN y_pond p ON p.id = r.pond_id " +
            "LEFT JOIN eiot_device_info d ON d.id = r.device_id " +
            "LEFT JOIN y_module m ON m.id = r.module_id " +
            "WHERE 1=1 " +
            "<if test=\"tenantId != null\"> AND r.tenant_id = #{tenantId}</if> " +
            "<if test=\"farmId != null\"> AND r.farm_id = #{farmId}</if> " +
            "<if test=\"pondId != null\"> AND r.pond_id = #{pondId}</if> " +
            "<if test=\"moduleId != null\"> AND r.module_id = #{moduleId}</if> " +
            "<if test=\"level != null and level != ''\"> AND r.level = #{level}</if> " +
            "<if test=\"name != null and name != ''\"> AND r.name LIKE CONCAT('%',#{name},'%')</if> " +
            "<if test=\"readFlg != null\"> AND r.read_flg = #{readFlg}</if> " +
            "ORDER BY r.id DESC" +
            "</script>")
    Page<AlertRecordDO> selectPageWithJoin(Page<?> page,
                                           @Param("tenantId") Long tenantId,
                                           @Param("farmId") Long farmId,
                                           @Param("pondId") Long pondId,
                                           @Param("moduleId") Long moduleId,
                                           @Param("level") String level,
                                           @Param("name") String name,
                                           @Param("readFlg") Boolean readFlg);

}
