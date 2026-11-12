package com.enjoyiot.module.ai.dal.mysql.analysis;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondFixedParamsDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface YPondFixedParamsMapper extends BaseMapperX<YPondFixedParamsDO> {

    default YPondFixedParamsDO selectByPondId(Long pondId) {
        return selectOne(YPondFixedParamsDO::getPondId, pondId);
    }

    default int deleteByPondId(Long pondId) {
        return delete(new LambdaQueryWrapperX<YPondFixedParamsDO>()
                .eq(YPondFixedParamsDO::getPondId, pondId));
    }

}
