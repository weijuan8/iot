package com.enjoyiot.module.eiot.service.farm;

import com.enjoyiot.module.eiot.controller.admin.farm.vo.YUnitDetailVO;

/**
 * 单元(模块)聚合 Service 接口
 * 为小程序「单元」Tab 页提供聚合查询
 *
 * @author EnjoyIot
 */
public interface YUnitService {

    /**
     * 获取单元详情（聚合：模块信息 + 设备控制 + 传感器数据 + 告警）
     *
     * @param moduleId 模块ID
     * @return 单元详情
     */
    YUnitDetailVO getUnitDetail(Long moduleId);
}
