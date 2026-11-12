package com.enjoyiot.module.eiot.service.farm;

import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;
import com.enjoyiot.module.eiot.dal.dataobject.deviceinfo.EiotDeviceInfoDO;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 模块（设备区域） Service 接口
 *
 * @author EnjoyIot
 */
public interface YModuleService {

    /**
     * 创建模块
     */
    Long createModule(@Valid YModuleSaveReqVO createReqVO);

    /**
     * 更新模块
     */
    void updateModule(@Valid YModuleSaveReqVO updateReqVO);

    /**
     * 删除模块
     */
    void deleteModule(Long id);

    /**
     * 获取模块详情
     */
    YModuleRespVO getModule(Long id);

    /**
     * 获取模块分页列表
     */
    PageResult<YModuleRespVO> getModulePage(YModulePageReqVO pageReqVO);

    /**
     * 为模块绑定设备
     */
    void bindDevice(Long moduleId, Long deviceId);

    /**
     * 为模块解绑设备
     */
    void unbindDevice(Long moduleId, Long deviceId);

    /**
     * 获取模块下已绑定的设备列表
     */
    List<YModuleDeviceRespVO> getModuleDevices(Long moduleId);

    /**
     * 获取某个塘口下的模块简单列表
     */
    List<YModuleSimpleRespVO> getModuleListByPondId(Long pondId);

    /**
     * 获取未被任何模块绑定的设备列表（用于穿梭框左侧可选设备）
     */
    List<EiotDeviceInfoDO> getUnboundDeviceList();
}
