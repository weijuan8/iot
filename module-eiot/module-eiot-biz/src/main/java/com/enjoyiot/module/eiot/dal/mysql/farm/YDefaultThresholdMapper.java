package com.enjoyiot.module.eiot.dal.mysql.farm;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YDefaultThresholdDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 默认告警阈值模板 Mapper
 *
 * @author EnjoyIot
 */
@Mapper
public interface YDefaultThresholdMapper extends BaseMapperX<YDefaultThresholdDO> {
}
