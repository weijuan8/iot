/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com | Tel: 19918996474
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot] | Tel: 19918996474
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */
package com.enjoyiot.module.eiot.api.module;

import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDeviceDO;
import com.enjoyiot.module.eiot.dal.mysql.farm.YModuleDeviceMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YModuleMapper;
import com.enjoyiot.module.eiot.util.EiotTenantUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 模块 API 实现（供 GEIO 组件等系统层调用）
 *
 * @author EnjoyIot
 */
@Slf4j
@Service
public class YModuleApiImpl implements YModuleApi {

    @Resource
    private YModuleMapper moduleMapper;

    @Resource
    private YModuleDeviceMapper moduleDeviceMapper;

    @Override
    @TenantIgnore
    public YModuleInfo getById(Long moduleId) {
        if (moduleId == null) {
            return null;
        }
        // 绑定/查询逻辑与租户无关：跳过当前 SQL 的 tenant_id 拦截，使用 executeIgnoreResult
        // 让 TenantDatabaseInterceptor 完全不拼 tenant_id 条件，可命中任意租户的模块。
        // 旧的 TenantUtils.execute(PLATFORM_TENANT_ID) 会把 ignore 强制设回 false
        // 并强行注入 WHERE tenant_id=1，导致非平台租户(如 tenant_id=165)的模块查不到。
        YModuleDO module = TenantUtils.executeIgnoreResult(() ->
                moduleMapper.selectById(moduleId));
        if (module == null) {
            log.debug("YModuleApiImpl.getById: module not found, moduleId={}", moduleId);
            return null;
        }
        return YModuleInfo.builder()
                .id(module.getId())
                .moduleCode(module.getModuleCode())
                .name(module.getModuleName())
                .farmId(module.getFarmId())
                .pondId(module.getPondId())
                .tenantId(module.getTenantId())
                .build();
    }

    @Override
    @TenantIgnore
    public void bindDeviceInternal(Long moduleId, Long deviceId) {
        if (moduleId == null || deviceId == null) {
            log.warn("bindDeviceInternal: invalid args, moduleId={}, deviceId={}", moduleId, deviceId);
            return;
        }
        // 绑定逻辑与租户无关：同样使用 executeIgnoreResult 跨租户读 y_module
        YModuleDO module = TenantUtils.executeIgnoreResult(() ->
                moduleMapper.selectById(moduleId));
        if (module == null) {
            log.warn("bindDeviceInternal: module not found, moduleId={}", moduleId);
            return;
        }
        // 直接插入 y_module_device（不继承 BaseDO，不走租户/软删除拦截器）
        // 唯一索引 uk_device_id 兜底：device_id 已存在时会抛异常，由调用方处理
        YModuleDeviceDO bindDO = new YModuleDeviceDO();
        bindDO.setModuleId(moduleId);
        bindDO.setDeviceId(deviceId);
        bindDO.setFarmId(module.getFarmId());
        bindDO.setPondId(module.getPondId());
        bindDO.setOwnerTenantId(module.getTenantId());
        bindDO.setCreator("system");
        bindDO.setUpdater("system");
        bindDO.setCreateTime(LocalDateTime.now());
        bindDO.setUpdateTime(LocalDateTime.now());
        moduleDeviceMapper.insert(bindDO);
        log.info("bindDeviceInternal: success, moduleId={}, deviceId={}, farmId={}, ownerTenantId={}",
                moduleId, deviceId, module.getFarmId(), module.getTenantId());
    }
}