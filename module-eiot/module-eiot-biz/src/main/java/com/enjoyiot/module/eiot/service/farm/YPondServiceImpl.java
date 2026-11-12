package com.enjoyiot.module.eiot.service.farm;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import com.enjoyiot.framework.common.util.object.BeanUtils;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YDefaultThresholdDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YFarmDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YModuleDeviceDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YPondDO;
import com.enjoyiot.module.eiot.dal.dataobject.thingmodel.ThingModelDO;
import com.enjoyiot.module.eiot.dal.mysql.farm.YDefaultThresholdMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YFarmMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YModuleDeviceMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YPondMapper;
import com.enjoyiot.module.eiot.dal.mysql.thingmodel.ThingModelMapper;
import com.enjoyiot.module.eiot.dal.redis.RedisKeyConstants;
import com.enjoyiot.module.eiot.util.EiotTenantUtils;
import com.enjoyiot.module.system.api.tenant.TenantApi;
import com.enjoyiot.module.system.api.tenant.dto.TenantSimpleRespDTO;
import com.enjoyiot.module.system.api.user.AdminUserApi;
import com.enjoyiot.module.system.api.user.dto.AdminUserRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 塘口 Service 实现类
 *
 * @author EnjoyIot
 */
@Service
@Validated
@Slf4j
public class YPondServiceImpl implements YPondService {

    @Resource
    private YPondMapper pondMapper;

    @Resource
    private YFarmMapper farmMapper;

    @Resource
    private TenantApi tenantApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private YDefaultThresholdMapper yDefaultThresholdMapper;

    @Resource
    private YModuleDeviceMapper yModuleDeviceMapper;

    @Resource
    private ThingModelMapper thingModelMapper;

    @Resource
    private CacheManager cacheManager;

    @Override
    @TenantIgnore // 绕过自动租户过滤，使内部校验能跨租户查询
    public Long createPond(YPondSaveReqVO createReqVO) {
        YFarmDO farm = validateFarmExists(createReqVO.getFarmId());
        // 权限：平台管理员可操作任意租户；普通租户管理员只能操作自己 + 自己的一级子租户
        EiotTenantUtils.validateTenantPermission(farm.getTenantId(), tenantApi,
                ErrorCodeConstants.POND_PERMISSION_DENIED);
        // 自动生成塘口编码（如果前端没传）
        if (StrUtil.isBlank(createReqVO.getPondCode())) {
            createReqVO.setPondCode(generatePondCode());
        }
        validatePondCodeUnique(createReqVO.getPondCode(), null);
        YPondDO pondDO = BeanUtils.toBean(createReqVO, YPondDO.class);
        pondDO.setTenantId(farm.getTenantId());
        // 处理 alertEnabled 字段：前端传 "是"/"否"
        if (StrUtil.isNotBlank(createReqVO.getAlertEnabled())) {
            pondDO.setAlertEnabled("是".equals(createReqVO.getAlertEnabled()));
        }
        // AI 分析默认开启
        if (createReqVO.getAiAnalysisEnabled() == null) {
            pondDO.setAiAnalysisEnabled(true);
        }
        // 处理日期字段
        pondDO.setBuiltAt(parseDate(createReqVO.getBuiltAt()));
        pondDO.setLastCleanAt(parseDate(createReqVO.getLastCleanAt()));
        pondDO.setLastDisinfectAt(parseDate(createReqVO.getLastDisinfectAt()));
        pondDO.setLastMaintenanceAt(parseDate(createReqVO.getLastMaintenanceAt()));
        pondDO.setNextMaintenancePlan(parseDate(createReqVO.getNextMaintenancePlan()));
        // 创建时始终从系统默认模板复制阈值（不允许用户自定义初始值）
        YDefaultThresholdDO def = yDefaultThresholdMapper.selectById(1L);
        if (def != null) {
            pondDO.setAlertThresholds(def.getThresholdsJson());
        }
        // 使用养殖场所属租户的上下文写入数据，确保租户归属正确
        TenantUtils.execute(farm.getTenantId(), () -> {
            pondMapper.insert(pondDO);
        });
        return pondDO.getId();
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，使内部校验能跨租户查询
    public void updatePond(YPondSaveReqVO updateReqVO) {
        YPondDO existingPond = validatePondExists(updateReqVO.getId());
        // 塘口一旦归属某个养殖场后，不允许修改 farmId
        if (updateReqVO.getFarmId() != null && !Objects.equals(existingPond.getFarmId(), updateReqVO.getFarmId())) {
            throw exception(ErrorCodeConstants.POND_FARM_ID_CANNOT_CHANGE);
        }
        YFarmDO farm = validateFarmExists(updateReqVO.getFarmId());
        // 权限：平台管理员可操作任意租户；普通租户管理员只能操作自己 + 自己的一级子租户
        EiotTenantUtils.validateTenantPermission(farm.getTenantId(), tenantApi,
                ErrorCodeConstants.POND_PERMISSION_DENIED);
        if (StrUtil.isNotBlank(updateReqVO.getPondCode())) {
            validatePondCodeUnique(updateReqVO.getPondCode(), updateReqVO.getId());
        }
        YPondDO updateObj = BeanUtils.toBean(updateReqVO, YPondDO.class);
        updateObj.setTenantId(farm.getTenantId());
        if (StrUtil.isNotBlank(updateReqVO.getAlertEnabled())) {
            updateObj.setAlertEnabled("是".equals(updateReqVO.getAlertEnabled()));
        }
        updateObj.setBuiltAt(parseDate(updateReqVO.getBuiltAt()));
        updateObj.setLastCleanAt(parseDate(updateReqVO.getLastCleanAt()));
        updateObj.setLastDisinfectAt(parseDate(updateReqVO.getLastDisinfectAt()));
        updateObj.setLastMaintenanceAt(parseDate(updateReqVO.getLastMaintenanceAt()));
        updateObj.setNextMaintenancePlan(parseDate(updateReqVO.getNextMaintenancePlan()));
        // 如果修改了告警阈值或模板，清除该塘口下所有设备的缓存
        boolean thresholdsChanged = !Objects.equals(existingPond.getAlertThresholds(), updateReqVO.getAlertThresholds());
        boolean templatesChanged = !Objects.equals(existingPond.getAlertTemplateIds(), updateReqVO.getAlertTemplateIds());
        if (thresholdsChanged || templatesChanged) {
            List<YModuleDeviceDO> devices = yModuleDeviceMapper.selectList(
                    new LambdaQueryWrapperX<YModuleDeviceDO>()
                            .eq(YModuleDeviceDO::getPondId, updateReqVO.getId()));
            for (YModuleDeviceDO d : devices) {
                Cache cache = cacheManager.getCache(RedisKeyConstants.DEVICE_ID);
                if (cache != null) {
                    cache.evict(d.getDeviceId().toString());
                }
            }
        }
        // 使用养殖场所属租户的上下文写入数据
        TenantUtils.execute(farm.getTenantId(), () -> {
            pondMapper.updateById(updateObj);
        });
    }

    @Override
    @TenantIgnore
    public void updateAiAnalysisEnabled(Long id, Boolean enabled) {
        YPondDO pond = validatePondExists(id);
        YPondDO update = new YPondDO();
        update.setId(id);
        update.setAiAnalysisEnabled(enabled);
        TenantUtils.execute(pond.getTenantId(), () -> {
            pondMapper.updateById(update);
        });
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，使 validatePondExists 能跨租户查询
    public void deletePond(Long id) {
        YPondDO existingPond = validatePondExists(id);
        // 权限：只能删除自己（或自己子租户）名下的塘口
        EiotTenantUtils.validateTenantPermission(existingPond.getTenantId(), tenantApi,
                ErrorCodeConstants.POND_PERMISSION_DENIED);
        // 使用塘口所属租户的上下文执行删除
        TenantUtils.execute(existingPond.getTenantId(), () -> {
            pondMapper.deleteById(id);
        });
    }

    @Override
    @TenantIgnore
    public YPondRespVO getPond(Long id) {
        YPondDO pondDO = validatePondExists(id);
        YPondRespVO vo = BeanUtils.toBean(pondDO, YPondRespVO.class);
        // 填充关联名称（绕过租户过滤，跨租户查询养殖场信息）
        YFarmDO farm = farmMapper.selectById(pondDO.getFarmId());
        if (farm != null) {
            vo.setFarmName(farm.getFarmName());
            vo.setTenantId(farm.getTenantId());
        }
        // alertEnabled boolean → 是/否
        if (pondDO.getAlertEnabled() != null) {
            vo.setAlertEnabled(pondDO.getAlertEnabled() ? "是" : "否");
        }
        // 填充审计字段和租户名称
        enrichSinglePondVO(vo, pondDO);
        return vo;
    }

    @Override
    @TenantIgnore
    public PageResult<YPondRespVO> getPondPage(YPondPageReqVO pageReqVO) {
        LambdaQueryWrapperX<YPondDO> wrapper = new LambdaQueryWrapperX<>();
        Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
        if (!EiotTenantUtils.isPlatformAdmin(currentTenantId)) {
            wrapper.eq(YPondDO::getTenantId, currentTenantId);
        } else if (pageReqVO.getTenantId() != null) {
            wrapper.eq(YPondDO::getTenantId, pageReqVO.getTenantId());
        }
        if (pageReqVO.getFarmId() != null) {
            wrapper.eq(YPondDO::getFarmId, pageReqVO.getFarmId());
        }
        if (StrUtil.isNotBlank(pageReqVO.getPondName())) {
            wrapper.like(YPondDO::getPondName, pageReqVO.getPondName());
        }
        if (StrUtil.isNotBlank(pageReqVO.getPondType())) {
            wrapper.eq(YPondDO::getPondType, pageReqVO.getPondType());
        }
        if (StrUtil.isNotBlank(pageReqVO.getStatus())) {
            wrapper.eq(YPondDO::getStatus, pageReqVO.getStatus());
        }
        wrapper.orderByDesc(YPondDO::getCreateTime);
        PageResult<YPondDO> pageResult = pondMapper.selectPage(pageReqVO, wrapper);
        List<YPondDO> doList = pageResult.getList();
        List<YPondRespVO> voList = BeanUtils.toBean(doList, YPondRespVO.class);
        enrichPondRespVOList(voList, doList);
        return new PageResult<>(voList, pageResult.getTotal());
    }

    @Override
    @TenantIgnore
    public List<YPondSimpleRespVO> getPondListByFarmId(Long farmId) {
        // 塘口已通过 farmId 关联到养殖场，养殖场层面已有租户权限控制，此处无需再过滤 tenant_id
        LambdaQueryWrapperX<YPondDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(YPondDO::getFarmId, farmId);
        wrapper.orderByDesc(YPondDO::getCreateTime);
        List<YPondDO> list = pondMapper.selectList(wrapper);
        return BeanUtils.toBean(list, YPondSimpleRespVO.class);
    }

    // ========== 私有方法 ==========

    private YPondDO validatePondExists(Long id) {
        YPondDO pondDO = pondMapper.selectById(id);
        if (pondDO == null) {
            throw exception(ErrorCodeConstants.POND_NOT_EXISTS);
        }
        return pondDO;
    }

    private YFarmDO validateFarmExists(Long farmId) {
        YFarmDO farmDO = farmMapper.selectById(farmId);
        if (farmDO == null) {
            throw exception(ErrorCodeConstants.FARM_NOT_EXISTS);
        }
        return farmDO;
    }

    private void validatePondCodeUnique(String pondCode, Long excludeId) {
        LambdaQueryWrapperX<YPondDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(YPondDO::getPondCode, pondCode);
        if (excludeId != null) {
            wrapper.ne(YPondDO::getId, excludeId);
        }
        if (pondMapper.selectCount(wrapper) > 0) {
            throw exception(ErrorCodeConstants.POND_CODE_DUPLICATE);
        }
    }

    // 注：原 validatePlatformPermission()（要求 tenantId == 1）已移除，
    // 改为 EiotTenantUtils.validateTenantPermission(...) 以支持普通租户管理员新增/修改/删除。

    private LocalDate parseDate(String dateStr) {
        if (StrUtil.isBlank(dateStr)) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成塘口编码，格式：POND-yyyyMMdd-5位随机数
     */
    private String generatePondCode() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = RandomStringUtils.randomNumeric(5);
        return "POND-" + dateStr + "-" + random;
    }

    /**
     * 批量填充审计字段、关联名称和租户名称
     */
    private void enrichPondRespVOList(List<YPondRespVO> voList, List<YPondDO> doList) {
        Map<Long, String> tenantNameMap = loadTenantNameMap();
        Map<Long, String> farmNameMap = loadFarmNameMap(doList);
        Map<Long, String> userNameMap = loadPondUserNameMap(doList);
        for (int i = 0; i < voList.size(); i++) {
            YPondRespVO vo = voList.get(i);
            YPondDO d = doList.get(i);
            vo.setCreatedAt(d.getCreateTime());
            vo.setUpdatedAt(d.getUpdateTime());
            vo.setCreatedBy(userNameMap.getOrDefault(parseUserId(d.getCreator()), d.getCreator()));
            vo.setUpdatedBy(userNameMap.getOrDefault(parseUserId(d.getUpdater()), d.getUpdater()));
            // BeanUtils 转 Boolean→String 得到 "true"/"false"，需转为 "是"/"否"
            if (vo.getAlertEnabled() == null) {
                vo.setAlertEnabled("否");
            } else {
                vo.setAlertEnabled("true".equalsIgnoreCase(vo.getAlertEnabled()) ? "是" : "否");
            }
            vo.setFarmName(farmNameMap.getOrDefault(vo.getFarmId(), ""));
            vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
        }
    }

    private void enrichSinglePondVO(YPondRespVO vo, YPondDO pondDO) {
        vo.setCreatedAt(pondDO.getCreateTime());
        vo.setUpdatedAt(pondDO.getUpdateTime());
        Map<Long, String> userNameMap = loadPondUserNameMap(List.of(pondDO));
        vo.setCreatedBy(userNameMap.getOrDefault(parseUserId(pondDO.getCreator()), pondDO.getCreator()));
        vo.setUpdatedBy(userNameMap.getOrDefault(parseUserId(pondDO.getUpdater()), pondDO.getUpdater()));
        Map<Long, String> tenantNameMap = loadTenantNameMap();
        vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
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

    /**
     * 批量加载 farmId → farmName 映射
     */
    private Map<Long, String> loadFarmNameMap(List<YPondDO> doList) {
        List<Long> farmIds = doList.stream()
                .map(YPondDO::getFarmId)
                .distinct()
                .collect(Collectors.toList());
        if (farmIds.isEmpty()) return Map.of();
        try {
            List<YFarmDO> farms = farmMapper.selectList(
                    new LambdaQueryWrapperX<YFarmDO>().in(YFarmDO::getId, farmIds));
            return farms.stream().collect(Collectors.toMap(
                    YFarmDO::getId,
                    YFarmDO::getFarmName,
                    (a, b) -> a));
        } catch (Exception e) {
            log.warn("加载养殖场名称失败", e);
            return Map.of();
        }
    }

    /**
     * 批量加载用户 ID → 用户昵称的映射
     */
    private Map<Long, String> loadPondUserNameMap(List<YPondDO> doList) {
        List<Long> userIds = doList.stream()
                .flatMap(d -> Stream.of(d.getCreator(), d.getUpdater()))
                .map(this::parseUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) return Map.of();
        try {
            List<AdminUserRespDTO> users = adminUserApi.getUserList(userIds);
            return users.stream().collect(Collectors.toMap(
                    AdminUserRespDTO::getId,
                    AdminUserRespDTO::getNickname,
                    (a, b) -> a));
        } catch (Exception e) {
            log.warn("加载用户信息失败", e);
            return Map.of();
        }
    }

    private Long parseUserId(String userIdStr) {
        if (StrUtil.isBlank(userIdStr)) return null;
        try {
            return Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    @TenantIgnore // 已由 pond_id 限定范围，且先校验塘口存在，无需再叠加租户过滤
    public YPondPropertiesRespVO getPondProperties(Long pondId) {
        YPondPropertiesRespVO vo = new YPondPropertiesRespVO();
        vo.setProperties(new ArrayList<>());
        vo.setControls(new ArrayList<>());
        if (pondId == null) {
            return vo;
        }
        // 先确认塘口存在（避免越权查询他人塘口下的物模型）
        YPondDO pond = pondMapper.selectById(pondId);
        if (pond == null) {
            return vo;
        }

        // 一条 SQL 查出该塘口下所有设备对应的产品物模型
        List<ThingModelDO> models = thingModelMapper.selectByPondId(pondId);
        if (CollUtil.isEmpty(models)) {
            return vo;
        }

        Map<String, YPondPropertiesRespVO.PropertyOption> props = new LinkedHashMap<>();
        Map<String, YPondPropertiesRespVO.PropertyOption> ctrls = new LinkedHashMap<>();
        for (ThingModelDO tm : models) {
            if (StrUtil.isBlank(tm.getModel())) {
                continue;
            }
            ThingModel.Model model;
            try {
                model = JsonUtils.parseObject(tm.getModel(), ThingModel.Model.class);
            } catch (Exception e) {
                log.warn("[getPondProperties] 物模型解析失败, productKey={}", tm.getProductKey(), e);
                continue;
            }
            if (model == null || CollUtil.isEmpty(model.getProperties())) {
                continue;
            }
            for (ThingModel.Property p : model.getProperties()) {
                if (StrUtil.isBlank(p.getIdentifier())) {
                    continue;
                }
                // 读写 + 布尔 → 开关类，作为联动目标；其余作为告警源
                boolean isBoolSwitch = "rw".equals(p.getAccessMode())
                        && p.getDataType() != null
                        && ThingModel.DataType.TYPE_BOOL.equals(p.getDataType().getType());
                YPondPropertiesRespVO.PropertyOption opt = new YPondPropertiesRespVO.PropertyOption();
                opt.setIdentifier(p.getIdentifier());
                opt.setName(p.getName());
                opt.setUnit(p.getUnit());
                opt.setDataType(p.getDataType() != null ? p.getDataType().getType() : null);
                opt.setAccessMode(p.getAccessMode());
                if (isBoolSwitch) {
                    ctrls.putIfAbsent(p.getIdentifier(), opt);
                } else {
                    props.putIfAbsent(p.getIdentifier(), opt);
                }
            }
        }
        vo.setProperties(new ArrayList<>(props.values()));
        vo.setControls(new ArrayList<>(ctrls.values()));
        return vo;
    }
}
