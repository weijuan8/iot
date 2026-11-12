package com.enjoyiot.module.eiot.service.report;

import cn.hutool.core.util.StrUtil;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.framework.common.util.object.BeanUtils;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.framework.security.core.LoginUser;
import com.enjoyiot.framework.security.core.util.SecurityFrameworkUtils;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportPageReqVO;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportRespVO;
import com.enjoyiot.module.eiot.controller.admin.report.vo.YAquacultureReportSaveReqVO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YFarmDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YPondDO;
import com.enjoyiot.module.eiot.dal.dataobject.report.YAquacultureReportDO;
import com.enjoyiot.module.eiot.dal.mysql.farm.YFarmMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YPondMapper;
import com.enjoyiot.module.eiot.dal.mysql.report.YAquacultureReportMapper;
import com.enjoyiot.module.eiot.util.EiotTenantUtils;
import com.enjoyiot.module.system.api.tenant.TenantApi;
import com.enjoyiot.module.system.api.tenant.dto.TenantSimpleRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 种养上报 Service 实现类
 *
 * @author EnjoyIot
 */
@Service
@Validated
@Slf4j
public class YAquacultureReportServiceImpl implements YAquacultureReportService {

    @Resource
    private YAquacultureReportMapper reportMapper;

    @Resource
    private YFarmMapper farmMapper;

    @Resource
    private YPondMapper pondMapper;

    @Resource
    private TenantApi tenantApi;

    @Override
    @TenantIgnore // 绕过自动租户过滤，手动控制租户上下文写入
    public Long createReport(YAquacultureReportSaveReqVO createReqVO) {
        // 1. 验证塘口存在，并通过塘口获取所属租户
        YPondDO pondDO = pondMapper.selectById(createReqVO.getPondId());
        if (pondDO == null) {
            throw exception(ErrorCodeConstants.POND_NOT_EXISTS);
        }

        // 2. 验证养殖场存在
        YFarmDO farmDO = farmMapper.selectById(createReqVO.getFarmId());
        if (farmDO == null) {
            throw exception(ErrorCodeConstants.FARM_NOT_EXISTS);
        }

        // 3. 上报记录归属到塘口所在租户
        Long pondTenantId = pondDO.getTenantId();

        // 4. 权限校验：普通租户只能上报属于自己租户的塘口数据
        Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
        if (!EiotTenantUtils.isPlatformAdmin(currentTenantId)
                && !Objects.equals(currentTenantId, pondTenantId)) {
            throw exception(ErrorCodeConstants.REPORT_PERMISSION_DENIED);
        }

        // 5. 设置操作人（从当前登录用户获取）
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser != null) {
            if (createReqVO.getOperatorId() == null) {
                createReqVO.setOperatorId(loginUser.getId());
            }
            if (StrUtil.isBlank(createReqVO.getOperatorName())) {
                String nickname = loginUser.getInfo() != null
                        ? loginUser.getInfo().get(LoginUser.INFO_KEY_NICKNAME) : null;
                createReqVO.setOperatorName(StrUtil.isNotBlank(nickname) ? nickname : "");
            }
        }

        YAquacultureReportDO reportDO = BeanUtils.toBean(createReqVO, YAquacultureReportDO.class);
        // 上报记录归属到塘口所在租户
        reportDO.setTenantId(pondTenantId);

        // 处理上报时间
        if (StrUtil.isNotBlank(createReqVO.getReportTime())) {
            try {
                reportDO.setReportTime(LocalDateTime.parse(createReqVO.getReportTime(),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } catch (Exception e) {
                reportDO.setReportTime(LocalDateTime.now());
            }
        } else {
            reportDO.setReportTime(LocalDateTime.now());
        }

        // 6. 使用塘口所属租户上下文写入数据
        TenantUtils.execute(pondTenantId, () -> {
            reportMapper.insert(reportDO);
        });
        return reportDO.getId();
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，手动校验权限
    public void updateReport(YAquacultureReportSaveReqVO updateReqVO) {
        YAquacultureReportDO existingReport = validateReportExists(updateReqVO.getId());
        // 权限校验：普通租户只能更新自己租户的数据
        validateReportTenantPermission(existingReport);

        YAquacultureReportDO updateObj = BeanUtils.toBean(updateReqVO, YAquacultureReportDO.class);

        // 处理上报时间
        if (StrUtil.isNotBlank(updateReqVO.getReportTime())) {
            try {
                updateObj.setReportTime(LocalDateTime.parse(updateReqVO.getReportTime(),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } catch (Exception e) {
                updateObj.setReportTime(existingReport.getReportTime());
            }
        }

        // 使用上报记录所属租户上下文执行更新
        TenantUtils.execute(existingReport.getTenantId(), () -> {
            reportMapper.updateById(updateObj);
        });
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，手动校验权限
    public void deleteReport(Long id) {
        YAquacultureReportDO existingReport = validateReportExists(id);
        // 权限校验：普通租户只能删除自己租户的数据
        validateReportTenantPermission(existingReport);

        TenantUtils.execute(existingReport.getTenantId(), () -> {
            reportMapper.deleteById(id);
        });
    }

    @Override
    @TenantIgnore
    public YAquacultureReportRespVO getReport(Long id) {
        YAquacultureReportDO reportDO = validateReportExists(id);
        // 权限校验：普通租户只能查看自己租户的数据
        validateReportTenantPermission(reportDO);
        return convertToRespVO(reportDO);
    }

    @Override
    @TenantIgnore
    public PageResult<YAquacultureReportRespVO> getReportPage(YAquacultureReportPageReqVO pageReqVO) {
        LambdaQueryWrapperX<YAquacultureReportDO> wrapper = new LambdaQueryWrapperX<>();

        // 租户过滤：前端传了 tenantId 则按指定租户筛选；否则平台管理员看全部，普通租户只看自己的
        if (pageReqVO.getTenantId() != null) {
            wrapper.eq(YAquacultureReportDO::getTenantId, pageReqVO.getTenantId());
        } else {
            Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
            if (!EiotTenantUtils.isPlatformAdmin(currentTenantId)) {
                // 普通租户只能看自己的数据
                wrapper.eq(YAquacultureReportDO::getTenantId, currentTenantId);
            }
            // 平台管理员且未指定 tenantId 时，看全部
        }

        // 可选筛选条件
        if (pageReqVO.getFarmId() != null) {
            wrapper.eq(YAquacultureReportDO::getFarmId, pageReqVO.getFarmId());
        }
        if (pageReqVO.getPondId() != null) {
            wrapper.eq(YAquacultureReportDO::getPondId, pageReqVO.getPondId());
        }
        if (StrUtil.isNotBlank(pageReqVO.getOperatorName())) {
            wrapper.like(YAquacultureReportDO::getOperatorName, pageReqVO.getOperatorName());
        }
        if (StrUtil.isNotBlank(pageReqVO.getReportTimeStart())) {
            try {
                LocalDateTime start = LocalDateTime.parse(pageReqVO.getReportTimeStart(),
                        pageReqVO.getReportTimeStart().length() == 10
                                ? DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                wrapper.ge(YAquacultureReportDO::getReportTime, start);
            } catch (Exception e) {
                log.warn("解析开始时间失败: {}", pageReqVO.getReportTimeStart(), e);
            }
        }
        if (StrUtil.isNotBlank(pageReqVO.getReportTimeEnd())) {
            try {
                LocalDateTime end = LocalDateTime.parse(pageReqVO.getReportTimeEnd(),
                        pageReqVO.getReportTimeEnd().length() == 10
                                ? DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                // 如果是日期格式（10位），扩展到当天结束
                if (pageReqVO.getReportTimeEnd().length() == 10) {
                    end = end.plusDays(1).minusSeconds(1);
                }
                wrapper.le(YAquacultureReportDO::getReportTime, end);
            } catch (Exception e) {
                log.warn("解析结束时间失败: {}", pageReqVO.getReportTimeEnd(), e);
            }
        }

        wrapper.orderByDesc(YAquacultureReportDO::getReportTime);
        PageResult<YAquacultureReportDO> pageResult = reportMapper.selectPage(pageReqVO, wrapper);
        List<YAquacultureReportDO> doList = pageResult.getList();

        List<YAquacultureReportRespVO> voList = doList.stream()
                .map(this::convertToRespVO)
                .collect(Collectors.toList());

        // 批量填充关联名称
        enrichRespVOList(voList, doList);

        return new PageResult<>(voList, pageResult.getTotal());
    }

    // ========== 私有方法 ==========

    private YAquacultureReportRespVO convertToRespVO(YAquacultureReportDO reportDO) {
        YAquacultureReportRespVO vo = BeanUtils.toBean(reportDO, YAquacultureReportRespVO.class);

        // 投喂信息：投喂了才组装对象，否则为null
        if (Boolean.TRUE.equals(reportDO.getFeedingEnabled())) {
            YAquacultureReportRespVO.FeedingInfoVO feeding = new YAquacultureReportRespVO.FeedingInfoVO();
            feeding.setAmountKg(reportDO.getFeedingAmountKg());
            feeding.setFeedType(reportDO.getFeedingType());
            feeding.setFeedingStatus(reportDO.getFeedingStatus());
            vo.setFeeding(feeding);
        } else {
            vo.setFeeding(null);
        }

        // 今日操作
        YAquacultureReportRespVO.OperationsVO operations = new YAquacultureReportRespVO.OperationsVO();
        operations.setWaterChange(reportDO.getWaterChange() != null ? reportDO.getWaterChange() : false);
        operations.setWaterRatio(reportDO.getWaterRatio());
        operations.setBottomClean(reportDO.getBottomClean() != null ? reportDO.getBottomClean() : false);
        operations.setProbiotics(reportDO.getProbiotics() != null ? reportDO.getProbiotics() : false);
        operations.setProbioticsName(reportDO.getProbioticsName());
        operations.setMedication(reportDO.getMedication() != null ? reportDO.getMedication() : false);
        operations.setMedicationName(reportDO.getMedicationName());
        vo.setOperations(operations);

        // 水体观察
        YAquacultureReportRespVO.WaterObsVO waterObs = new YAquacultureReportRespVO.WaterObsVO();
        waterObs.setWaterColor(reportDO.getWaterColor());
        waterObs.setFloatingHead(reportDO.getFloatingHead());
        waterObs.setDeathCount(reportDO.getDeathCount() != null ? reportDO.getDeathCount() : 0);
        waterObs.setDisease(reportDO.getDisease() != null ? reportDO.getDisease() : false);
        waterObs.setWaterQualityChange(reportDO.getWaterQualityChange() != null ? reportDO.getWaterQualityChange() : false);
        vo.setWaterObs(waterObs);

        vo.setCreatedAt(reportDO.getCreateTime());

        return vo;
    }

    /**
     * 批量填充关联名称（养殖场/塘口名称、租户名称）
     */
    private void enrichRespVOList(List<YAquacultureReportRespVO> voList, List<YAquacultureReportDO> doList) {
        // 收集所有 farmId, pondId
        Set<Long> farmIds = doList.stream().map(YAquacultureReportDO::getFarmId).collect(Collectors.toSet());
        Set<Long> pondIds = doList.stream().map(YAquacultureReportDO::getPondId).collect(Collectors.toSet());

        // 批量加载名称映射
        Map<Long, YFarmDO> farmMap = loadFarmMap(farmIds);
        Map<Long, YPondDO> pondMap = loadPondMap(pondIds);
        Map<Long, String> tenantNameMap = loadTenantNameMap();

        for (YAquacultureReportRespVO vo : voList) {
            // 养殖场
            YFarmDO farm = farmMap.get(vo.getFarmId());
            if (farm != null) {
                vo.setFarmName(farm.getFarmName());
                vo.setFarmCode(farm.getFarmCode());
            }
            // 塘口
            YPondDO pond = pondMap.get(vo.getPondId());
            if (pond != null) {
                vo.setPondName(pond.getPondName());
            }
            // 租户
            vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
        }
    }

    private Map<Long, YFarmDO> loadFarmMap(Set<Long> farmIds) {
        if (farmIds.isEmpty()) return Map.of();
        try {
            List<YFarmDO> farms = farmMapper.selectBatchIds(farmIds);
            return farms.stream().collect(Collectors.toMap(YFarmDO::getId, f -> f, (a, b) -> a));
        } catch (Exception e) {
            log.warn("加载养殖场信息失败", e);
            return Map.of();
        }
    }

    private Map<Long, YPondDO> loadPondMap(Set<Long> pondIds) {
        if (pondIds.isEmpty()) return Map.of();
        try {
            List<YPondDO> ponds = pondMapper.selectBatchIds(pondIds);
            return ponds.stream().collect(Collectors.toMap(YPondDO::getId, p -> p, (a, b) -> a));
        } catch (Exception e) {
            log.warn("加载塘口信息失败", e);
            return Map.of();
        }
    }

    private Map<Long, String> loadTenantNameMap() {
        try {
            List<TenantSimpleRespDTO> tenants = tenantApi.getTenantList();
            return tenants.stream().collect(Collectors.toMap(
                    TenantSimpleRespDTO::getId,
                    TenantSimpleRespDTO::getName,
                    (a, b) -> a));
        } catch (Exception e) {
            log.warn("加载租户列表失败", e);
            return Map.of();
        }
    }

    private YAquacultureReportDO validateReportExists(Long id) {
        YAquacultureReportDO reportDO = reportMapper.selectById(id);
        if (reportDO == null) {
            throw exception(ErrorCodeConstants.REPORT_NOT_EXISTS);
        }
        return reportDO;
    }

    /**
     * 校验上报记录租户权限：普通租户只能操作自己租户的数据，平台管理员不受限
     */
    private void validateReportTenantPermission(YAquacultureReportDO reportDO) {
        Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
        if (!EiotTenantUtils.isPlatformAdmin(currentTenantId)
                && !Objects.equals(currentTenantId, reportDO.getTenantId())) {
            throw exception(ErrorCodeConstants.REPORT_PERMISSION_DENIED);
        }
    }

}
