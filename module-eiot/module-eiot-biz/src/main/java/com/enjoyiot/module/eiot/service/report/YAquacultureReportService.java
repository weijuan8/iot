package com.enjoyiot.module.eiot.service.report;

import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportPageReqVO;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportRespVO;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportSaveReqVO;

import jakarta.validation.Valid;

/**
 * 种养上报 Service 接口
 *
 * @author EnjoyIot
 */
public interface YAquacultureReportService {

    /**
     * 创建上报记录
     */
    Long createReport(@Valid YAquacultureReportSaveReqVO createReqVO);

    /**
     * 更新上报记录
     */
    void updateReport(@Valid YAquacultureReportSaveReqVO updateReqVO);

    /**
     * 删除上报记录（逻辑删除）
     */
    void deleteReport(Long id);

    /**
     * 获取上报记录详情
     */
    YAquacultureReportRespVO getReport(Long id);

    /**
     * 分页查询上报记录
     */
    PageResult<YAquacultureReportRespVO> getReportPage(YAquacultureReportPageReqVO pageReqVO);
}
