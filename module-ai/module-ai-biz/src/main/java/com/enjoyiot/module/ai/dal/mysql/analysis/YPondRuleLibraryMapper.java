package com.enjoyiot.module.ai.dal.mysql.analysis;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YPondRuleLibraryDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface YPondRuleLibraryMapper extends BaseMapperX<YPondRuleLibraryDO> {

    default List<YPondRuleLibraryDO> selectListByPondId(Long pondId) {
        return selectList(new LambdaQueryWrapperX<YPondRuleLibraryDO>()
                .eq(YPondRuleLibraryDO::getPondId, pondId)
                .orderByDesc(YPondRuleLibraryDO::getCredibility)
                .orderByDesc(YPondRuleLibraryDO::getId));
    }

    /**
     * 查询塘口可信度最高的前 N 条规律（供投喂 AI 使用，避免规律过多稀释 prompt）
     *
     * @param pondId 塘口ID
     * @param limit  最多返回条数
     */
    default List<YPondRuleLibraryDO> selectTopListByPondId(Long pondId, int limit) {
        return selectList(new LambdaQueryWrapperX<YPondRuleLibraryDO>()
                .eq(YPondRuleLibraryDO::getPondId, pondId)
                .orderByDesc(YPondRuleLibraryDO::getCredibility)
                .orderByDesc(YPondRuleLibraryDO::getId)
                .last("LIMIT " + limit));
    }

}
