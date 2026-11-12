package com.enjoyiot.module.eiot.service.alert;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import com.enjoyiot.module.eiot.api.alert.dto.AlertRecord;
import com.enjoyiot.module.eiot.controller.admin.alertconfig.vo.AlertRecordPageReq;
import com.enjoyiot.module.eiot.convert.AlertRecordConvert;
import com.enjoyiot.module.eiot.dal.dataobject.AlertRecordDO;
import com.enjoyiot.module.eiot.dal.mysql.AlertRecordMapper;
import com.enjoyiot.module.eiot.util.EiotTenantUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 告警消息 Service 实现
 *
 * @author EnjoyIot
 */
@Slf4j
@Service
public class AlertServiceImpl implements AlertService {

    @Resource
    private AlertRecordMapper alertRecordMapper;

    /**
     * 告警消息分页查询。
     *
     * 使用 @TenantIgnore 绕过自动租户拦截器 + 自定义联表 SQL（LEFT JOIN），
     * 一条 SQL 同时查出告警数据和关联名称（养殖场、塘口、设备、模块），
     * 避免因租户隔离导致 selectBatchIds 查不到关联名称的问题。
     *
     * 权限规则：
     * - 平台管理员（tenant_id=1）：默认查看所有租户数据，可通过 tenantId 参数筛选指定租户
     * - 普通租户管理员：只能查看自己租户的数据，忽略请求中的 tenantId 参数
     */
    @Override
    @TenantIgnore
    public PageResult<AlertRecord> selectAlertRecordPage(AlertRecordPageReq request) {
        Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
        boolean isPlatformAdmin = EiotTenantUtils.isPlatformAdmin(currentTenantId);

        // 确定租户过滤参数
        Long filterTenantId;
        if (!isPlatformAdmin) {
            // 租户管理员：强制只看自己租户
            filterTenantId = currentTenantId;
        } else {
            // 平台管理员：按请求参数筛选（null = 全部）
            filterTenantId = request.getTenantId();
        }

        log.info("[selectAlertRecordPage] isPlatform={}, currentTenantId={}, filterTenantId={}, farmId={}, pondId={}, moduleId={}",
                isPlatformAdmin, currentTenantId, filterTenantId,
                request.getFarmId(), request.getPondId(), request.getModuleId());

        // 一条 SQL 联表查询，名称直接由 LEFT JOIN 带出
        Page<AlertRecordDO> page = new Page<>(request.getPageNo(), request.getPageSize());
        page = alertRecordMapper.selectPageWithJoin(page,
                filterTenantId,
                request.getFarmId(),
                request.getPondId(),
                request.getModuleId(),
                request.getLevel(),
                request.getName(),
                request.getReadFlg());

        log.info("[selectAlertRecordPage] 查询到 {} 条告警，共 {} 条", page.getRecords().size(), page.getTotal());

        // DO → DTO（MapStruct 自动映射 farmName/pondName/deviceName/moduleName）
        List<AlertRecord> alertRecords = AlertRecordConvert.INSTANCE.convertList(page.getRecords());

        return new PageResult<>(alertRecords, page.getTotal());
    }

    @Override
    public void markAsRead(Long id) {
        AlertRecordDO record = alertRecordMapper.selectById(id);
        if (record != null) {
            record.setReadFlg(true);
            alertRecordMapper.updateById(record);
        }
    }
}



