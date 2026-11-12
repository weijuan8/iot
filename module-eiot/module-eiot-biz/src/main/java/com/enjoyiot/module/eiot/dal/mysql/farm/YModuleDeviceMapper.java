package com.enjoyiot.module.eiot.dal.mysql.farm;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDeviceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模块-设备关联 Mapper
 *
 * @author EnjoyIot
 */
@Mapper
public interface YModuleDeviceMapper extends BaseMapperX<YModuleDeviceDO> {
}
