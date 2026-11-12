package com.enjoyiot.module.ai.dal.mysql.analysis;

import com.enjoyiot.framework.mybatis.core.mapper.BaseMapperX;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YDailyAnalysisResultDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;

@Mapper
public interface YDailyAnalysisResultMapper extends BaseMapperX<YDailyAnalysisResultDO> {

    default YDailyAnalysisResultDO selectByPondIdAndDate(Long pondId, LocalDate analysisDate) {
        return selectOne(new LambdaQueryWrapperX<YDailyAnalysisResultDO>()
                .eq(YDailyAnalysisResultDO::getPondId, pondId)
                .eq(YDailyAnalysisResultDO::getAnalysisDate, analysisDate));
    }

    /**
     * 查询塘口最新一条分析结果
     */
    default YDailyAnalysisResultDO selectLatestByPondId(Long pondId) {
        return selectOne(new LambdaQueryWrapperX<YDailyAnalysisResultDO>()
                .eq(YDailyAnalysisResultDO::getPondId, pondId)
                .orderByDesc(YDailyAnalysisResultDO::getAnalysisDate)
                .orderByDesc(YDailyAnalysisResultDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 查询塘口某日期之前的最近一条分析结果（用于复盘，默认昨天）
     */
    default YDailyAnalysisResultDO selectLatestBeforeDate(Long pondId, LocalDate date) {
        return selectOne(new LambdaQueryWrapperX<YDailyAnalysisResultDO>()
                .eq(YDailyAnalysisResultDO::getPondId, pondId)
                .lt(YDailyAnalysisResultDO::getAnalysisDate, date)
                .orderByDesc(YDailyAnalysisResultDO::getAnalysisDate)
                .orderByDesc(YDailyAnalysisResultDO::getId)
                .last("LIMIT 1"));
    }

}
