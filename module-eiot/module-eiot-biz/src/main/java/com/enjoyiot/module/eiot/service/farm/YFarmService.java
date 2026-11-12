package com.enjoyiot.module.eiot.service.farm;

import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 养殖场 Service 接口
 *
 * @author EnjoyIot
 */
public interface YFarmService {

    /**
     * 创建养殖场
     */
    Long createFarm(@Valid YFarmSaveReqVO createReqVO);

    /**
     * 更新养殖场
     */
    void updateFarm(@Valid YFarmSaveReqVO updateReqVO);

    /**
     * 删除养殖场（逻辑删除）
     */
    void deleteFarm(Long id);

    /**
     * 获取养殖场详情
     */
    YFarmRespVO getFarm(Long id);

    /**
     * 获取养殖场分页列表
     */
    PageResult<YFarmRespVO> getFarmPage(YFarmPageReqVO pageReqVO);

    /**
     * 获取养殖场简单列表（下拉选择用）
     *
     * @param tenantId 可选，按租户过滤；为 null 时平台管理员看全部，普通租户只看自己
     */
    List<YFarmSimpleRespVO> getFarmSimpleList(Long tenantId);
}
