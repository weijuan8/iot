package com.enjoyiot.module.ai.dal.mysql.analysis;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YFarmerHabitLibraryDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface YFarmerHabitLibraryMapper extends BaseMapperX<YFarmerHabitLibraryDO> {

    /**
     * 查询塘口相关习惯（塘口专属 + 农户全局，全局在前）
     *
     * @param pondId 塘口ID
     */
    default List<YFarmerHabitLibraryDO> selectListByPondId(Long pondId) {
        return selectList(new LambdaQueryWrapperX<YFarmerHabitLibraryDO>()
                .and(q -> q.eq(YFarmerHabitLibraryDO::getPondId, pondId)
                        .or().isNull(YFarmerHabitLibraryDO::getPondId))
                .orderByDesc(YFarmerHabitLibraryDO::getCredibility)
                .orderByDesc(YFarmerHabitLibraryDO::getId));
    }

    /**
     * 查询塘口可信度最高的前 N 条习惯（供投喂 AI 使用，避免习惯过多稀释 prompt）
     *
     * @param pondId 塘口ID
     * @param limit  最多返回条数
     */
    default List<YFarmerHabitLibraryDO> selectTopListByPondId(Long pondId, int limit) {
        return selectList(new LambdaQueryWrapperX<YFarmerHabitLibraryDO>()
                .and(q -> q.eq(YFarmerHabitLibraryDO::getPondId, pondId)
                        .or().isNull(YFarmerHabitLibraryDO::getPondId))
                .orderByDesc(YFarmerHabitLibraryDO::getCredibility)
                .orderByDesc(YFarmerHabitLibraryDO::getId)
                .last("LIMIT " + limit));
    }

}
