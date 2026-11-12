package com.enjoyiot.module.eiot.dal.mysql.farm;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YPondDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 塘口 Mapper
 *
 * @author EnjoyIot
 */
@Mapper
public interface YPondMapper extends BaseMapperX<YPondDO> {

    @Select("select count(id) from y_pond where pond_id = #{pandId}")
    Long countByPondId(Long pandId);
}
