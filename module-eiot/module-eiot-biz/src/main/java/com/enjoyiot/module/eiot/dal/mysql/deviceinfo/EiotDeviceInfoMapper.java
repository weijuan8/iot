
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
package com.enjoyiot.module.eiot.dal.mysql.deviceinfo;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.framework.mybatis.core.query.MPJLambdaWrapperX;
import com.enjoyiot.module.eiot.api.device.dto.DeviceShortInfo;
import com.enjoyiot.module.eiot.controller.admin.device.vo.DeviceInfoPageReqVO;
import com.enjoyiot.module.eiot.dal.dataobject.DeviceGroupDO;
import com.enjoyiot.module.eiot.dal.dataobject.deviceinfo.EiotDeviceInfoDO;
import com.enjoyiot.module.eiot.dal.dataobject.product.ProductDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 设备信息 Mapper
 *
 * @author EnjoyIot
 */
@Mapper
public interface EiotDeviceInfoMapper extends BaseMapperX<EiotDeviceInfoDO> {

    default PageResult<DeviceShortInfo> selectPage(DeviceInfoPageReqVO reqVO) {
        MPJLambdaWrapperX<EiotDeviceInfoDO> q = new MPJLambdaWrapperX<EiotDeviceInfoDO>()
                .selectAll(EiotDeviceInfoDO.class)
                .selectAs(ProductDO::getName, DeviceShortInfo::getProductName)
                .likeIfPresent(EiotDeviceInfoDO::getDn, reqVO.getDn())
                .eqIfPresent(EiotDeviceInfoDO::getProductKey, reqVO.getProductKey())
                .inIfPresent(EiotDeviceInfoDO::getProductKey, reqVO.getProductKeyList())
                .eqIfPresent(EiotDeviceInfoDO::getDeptId, reqVO.getDeptId())
                .betweenIfPresent(EiotDeviceInfoDO::getCreateTime, reqVO.getCreateTime())
                .likeIfPresent(EiotDeviceInfoDO::getName, reqVO.getName())
                .eqIfPresent(EiotDeviceInfoDO::getState, reqVO.getState())
                .eqIfPresent(EiotDeviceInfoDO::getSerialNo, reqVO.getSerialNo())
                .eqIfPresent(EiotDeviceInfoDO::getParentId, reqVO.getParentId())
                .orderByDesc(EiotDeviceInfoDO::getId);
        q.isNull(reqVO.getBindStatus() != null && !reqVO.getBindStatus(), EiotDeviceInfoDO::getParentId);
        q.exists(ObjectUtil.isNotNull(reqVO.getFilterTenantId()),
                "SELECT 1 FROM y_module_device md WHERE md.device_id = eiot_device_info.id AND md.owner_tenant_id = {0}",
                reqVO.getFilterTenantId());
        q.leftJoin(ProductDO.class, ProductDO::getProductKey, EiotDeviceInfoDO::getProductKey);
        if(ObjectUtil.isNotNull(reqVO.getGroupId())){
            q.innerJoin(DeviceGroupDO.class, DeviceGroupDO::getDeviceId, EiotDeviceInfoDO::getId);
            q.eq(DeviceGroupDO::getGroupId, reqVO.getGroupId());
        }
        q.eq(ObjectUtil.isNotNull(reqVO.getNodeType()), ProductDO::getNodeType, reqVO.getNodeType());
        return selectJoinPage(reqVO, DeviceShortInfo.class, q);
    }

    default Long selectCountByProductKey(String productKey){
        return selectCount(EiotDeviceInfoDO::getProductKey, productKey);
    }

    /**
     * 一条 SQL JOIN 出设备、养殖场、塘口、模块信息
     */
    @Select("SELECT d.*, md.farm_id, f.farm_name, md.pond_id, p.pond_name, md.module_id, md.owner_tenant_id, " +
            "p.alert_enabled AS pond_alert_enabled, p.alert_thresholds AS alert_thresholds_json, p.alert_template_ids AS alert_template_ids_json " +
            "FROM eiot_device_info d " +
            "LEFT JOIN y_module_device md ON md.device_id = d.id " +
            "LEFT JOIN y_farm f ON f.id = md.farm_id " +
            "LEFT JOIN y_pond p ON p.id = md.pond_id " +
            "WHERE d.id = #{id}")
    EiotDeviceInfoDO selectByIdWithFarmPond(@Param("id") Long id);

    /**
     * 修改设备名称（别名）
     *
     * <p>单表单字段更新，不做归属 / 权限校验。
     * 加 {@link InterceptorIgnore} 是为避免多租户拦截器自动拼接 tenant_id 条件
     * （eiot_device_info 已移除 tenant_id 列，见 _sql/mysql/upgrade/device_info_drop_tenant_id.sql）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE eiot_device_info SET name = #{name} WHERE id = #{id}")
    int updateName(@Param("id") Long id, @Param("name") String name);
}
