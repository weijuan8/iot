package com.enjoyiot.module.eiot.service.farm;

import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 塘口 Service 接口
 *
 * @author EnjoyIot
 */
public interface YPondService {

    /**
     * 创建塘口
     */
    Long createPond(@Valid YPondSaveReqVO createReqVO);

    /**
     * 更新塘口
     */
    void updatePond(@Valid YPondSaveReqVO updateReqVO);

    /**
     * 开启/关闭塘口 AI 分析（关闭后首页不返回 AI 数据，但每日分析仍执行）
     */
    void updateAiAnalysisEnabled(Long id, Boolean enabled);

    /**
     * 删除塘口（逻辑删除）
     */
    void deletePond(Long id);

    /**
     * 获取塘口详情
     */
    YPondRespVO getPond(Long id);

    /**
     * 获取塘口分页列表
     */
    PageResult<YPondRespVO> getPondPage(YPondPageReqVO pageReqVO);

    /**
     * 获取某个养殖场下的塘口简单列表
     */
    List<YPondSimpleRespVO> getPondListByFarmId(Long farmId);

    /**
     * 获取塘口下可配置的属性候选（告警源 + 联动目标）
     *
     * <p>数据来源：塘口 → 设备 → 产品物模型，一条 SQL 直接查出，
     * 用于阈值配置时提供「可用参数」下拉候选。
     */
    YPondPropertiesRespVO getPondProperties(Long pondId);
}
