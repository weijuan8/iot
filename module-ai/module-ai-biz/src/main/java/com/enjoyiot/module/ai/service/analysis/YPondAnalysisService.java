package com.enjoyiot.module.ai.service.analysis;

import com.enjoyiot.module.ai.controller.admin.analysis.vo.YHomePageRespVO;
import com.enjoyiot.module.ai.controller.admin.analysis.vo.YPondAnalysisRespVO;
import com.enjoyiot.module.ai.dal.dataobject.analysis.YDailyAnalysisResultDO;

import java.util.List;

/**
 * 塘口 AI 分析服务
 */
public interface YPondAnalysisService {

    /**
     * 分析单个塘口（查询数据 → 调用 AI → 存储结果 + 沉淀画像）
     *
     * @param pondId 塘口ID
     * @return 分析结果主表记录
     */
    YDailyAnalysisResultDO analyzePond(Long pondId);

    /**
     * 分析所有塘口（定时任务：每天06:00）
     *
     * @return 分析成功数量
     */
    int analyzeAllPond();

    /**
     * 查询塘口最新一条分析结果（含 AI 输出明细与塘口画像）
     *
     * @param pondId 塘口ID
     * @return 分析结果（不存在返回 null）
     */
    YPondAnalysisRespVO getLatestByPondId(Long pondId);

    /**
     * 首页数据拼接（对应 docs/home-page-api.md 契约，供前端首页渲染）
     *
     * @param pondId 塘口ID
     * @return 首页七模块数据（无分析记录返回 null）
     */
    YHomePageRespVO getHomePage(Long pondId);

    /**
     * 查询塘口最近 N 条分析结果（备用）
     *
     * @param pondId 塘口ID
     * @param count  条数
     */
    List<YDailyAnalysisResultDO> getRecentList(Long pondId, Integer count);

}
