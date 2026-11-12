package com.enjoyiot.module.eiot.service.farm;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.framework.common.util.object.BeanUtils;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;
import com.enjoyiot.module.eiot.dal.dataobject.deviceinfo.EiotDeviceInfoDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.*;
import com.enjoyiot.module.eiot.dal.mysql.deviceinfo.EiotDeviceInfoMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.*;
import com.enjoyiot.module.eiot.util.EiotTenantUtils;
import com.enjoyiot.module.system.api.tenant.TenantApi;
import com.enjoyiot.module.system.api.tenant.dto.TenantSimpleRespDTO;
import com.enjoyiot.module.system.api.user.AdminUserApi;
import com.enjoyiot.module.system.api.user.dto.AdminUserRespDTO;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 模块（设备区域） Service 实现类
 *
 * @author EnjoyIot
 */
@Service
@Validated
@Slf4j
public class YModuleServiceImpl implements YModuleService {

    @Resource
    private YModuleMapper moduleMapper;

    @Resource
    private YModuleDeviceMapper moduleDeviceMapper;

    @Resource
    private YFarmMapper farmMapper;

    @Resource
    private YPondMapper pondMapper;

    @Resource
    private EiotDeviceInfoMapper deviceInfoMapper;

    @Resource
    private TenantApi tenantApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    @TenantIgnore // 绕过自动租户过滤，使内部校验能跨租户查询
    public Long createModule(YModuleSaveReqVO createReqVO) {
        YFarmDO farm = validateFarmExists(createReqVO.getFarmId());
        validatePondExists(createReqVO.getPondId());
        // 权限：平台管理员可操作任意租户；普通租户管理员只能操作自己 + 自己的一级子租户
        EiotTenantUtils.validateTenantPermission(farm.getTenantId(), tenantApi,
                ErrorCodeConstants.MODULE_PERMISSION_DENIED);
        // 自动生成模块编码（如果前端没传）
        if (StrUtil.isBlank(createReqVO.getModuleCode())) {
            createReqVO.setModuleCode(generateModuleCode());
        }
        validateModuleCodeUnique(createReqVO.getModuleCode(), null);
        YModuleDO moduleDO = BeanUtils.toBean(createReqVO, YModuleDO.class);
        moduleDO.setTenantId(farm.getTenantId());
        moduleDO.setInstallAt(parseDate(createReqVO.getInstallAt()));
        // 使用养殖场所属租户的上下文写入数据
        TenantUtils.execute(farm.getTenantId(), () -> {
            moduleMapper.insert(moduleDO);
        });
        return moduleDO.getId();
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，使内部校验能跨租户查询
    public void updateModule(YModuleSaveReqVO updateReqVO) {
        validateModuleExists(updateReqVO.getId());
        YFarmDO farm = validateFarmExists(updateReqVO.getFarmId());
        validatePondExists(updateReqVO.getPondId());
        // 权限：平台管理员可操作任意租户；普通租户管理员只能操作自己 + 自己的一级子租户
        EiotTenantUtils.validateTenantPermission(farm.getTenantId(), tenantApi,
                ErrorCodeConstants.MODULE_PERMISSION_DENIED);
        validateModuleCodeUnique(updateReqVO.getModuleCode(), updateReqVO.getId());
        YModuleDO updateObj = BeanUtils.toBean(updateReqVO, YModuleDO.class);
        updateObj.setTenantId(farm.getTenantId());
        updateObj.setInstallAt(parseDate(updateReqVO.getInstallAt()));
        // 使用养殖场所属租户的上下文写入数据
        TenantUtils.execute(farm.getTenantId(), () -> {
            moduleMapper.updateById(updateObj);
        });
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，使 validateModuleExists 能跨租户查询
    public void deleteModule(Long id) {
        YModuleDO existingModule = validateModuleExists(id);
        // 权限：只能删除自己（或自己子租户）名下的模块
        EiotTenantUtils.validateTenantPermission(existingModule.getTenantId(), tenantApi,
                ErrorCodeConstants.MODULE_PERMISSION_DENIED);
        // 使用模块所属租户的上下文执行删除
        TenantUtils.execute(existingModule.getTenantId(), () -> {
            // 级联删除设备关联
            moduleDeviceMapper.delete(YModuleDeviceDO::getModuleId, id);
            moduleMapper.deleteById(id);
        });
    }

    @Override
    @TenantIgnore
    public YModuleRespVO getModule(Long id) {
        YModuleDO moduleDO = validateModuleExists(id);
        YModuleRespVO vo = BeanUtils.toBean(moduleDO, YModuleRespVO.class);
        // 填充关联名称（绕过租户过滤，跨租户查询）
        YFarmDO farm = farmMapper.selectById(moduleDO.getFarmId());
        if (farm != null) {
            vo.setFarmName(farm.getFarmName());
            vo.setTenantId(farm.getTenantId());
        }
        YPondDO pond = pondMapper.selectById(moduleDO.getPondId());
        if (pond != null) {
            vo.setPondName(pond.getPondName());
        }
        // 查询关联的设备列表
        vo.setDevices(getModuleDevices(id));
        // 填充审计字段、用户名称和租户名称（带租户上下文，确保 RPC 调用正常）
        Long tenantId = vo.getTenantId();
        if (tenantId != null) {
            TenantUtils.execute(tenantId, () -> enrichSingleModuleVO(vo, moduleDO));
        } else {
            enrichSingleModuleVO(vo, moduleDO);
        }
        return vo;
    }

    @Override
    @TenantIgnore
    public PageResult<YModuleRespVO> getModulePage(YModulePageReqVO pageReqVO) {
        // 使用 MPJLambdaWrapper 连表查询，LEFT JOIN y_module_device 统计设备数量
        MPJLambdaWrapper<YModuleDO> wrapper = new MPJLambdaWrapper<YModuleDO>()
                .selectAll(YModuleDO.class)
                .selectCount(YModuleDeviceDO::getId, "deviceCount")
                .leftJoin(YModuleDeviceDO.class, YModuleDeviceDO::getModuleId, YModuleDO::getId)
                .groupBy(YModuleDO::getId);

        Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
        if (!EiotTenantUtils.isPlatformAdmin(currentTenantId)) {
            wrapper.eq(YModuleDO::getTenantId, currentTenantId);
        } else if (pageReqVO.getTenantId() != null) {
            wrapper.eq(YModuleDO::getTenantId, pageReqVO.getTenantId());
        }
        if (pageReqVO.getFarmId() != null) {
            wrapper.eq(YModuleDO::getFarmId, pageReqVO.getFarmId());
        }
        if (pageReqVO.getPondId() != null) {
            wrapper.eq(YModuleDO::getPondId, pageReqVO.getPondId());
        }
        if (StrUtil.isNotBlank(pageReqVO.getModuleName())) {
            wrapper.like(YModuleDO::getModuleName, pageReqVO.getModuleName());
        }
        wrapper.orderByDesc(YModuleDO::getCreateTime);

        // selectJoinPage 内部自动处理 count + 分页
        PageResult<YModuleRespVO> pageResult = moduleMapper.selectJoinPage(
                pageReqVO, YModuleRespVO.class, wrapper);
        enrichModuleRespVOListFromJoin(pageResult.getList());
        return pageResult;
    }

    @Override
    @TenantIgnore  // 设备表有租户隔离，平台管理员需跨租户校验设备是否存在
    public void bindDevice(Long moduleId, Long deviceId) {
        YModuleDO moduleDO = validateModuleExists(moduleId);
        // 权限：只能给自己的（或自己子租户的）模块绑定设备
        EiotTenantUtils.validateTenantPermission(moduleDO.getTenantId(), tenantApi,
                ErrorCodeConstants.MODULE_PERMISSION_DENIED);
        EiotDeviceInfoDO deviceDO = deviceInfoMapper.selectById(deviceId);
        if (deviceDO == null) {
            throw exception(ErrorCodeConstants.MODULE_DEVICE_NOT_EXISTS);
        }
        // 绑定关系与租户无关，直接插入；冗余 farmId/pondId/ownerTenantId 便于查询设备所属养殖场、塘口和租户
        YModuleDeviceDO bindDO = new YModuleDeviceDO();
        bindDO.setModuleId(moduleId);
        bindDO.setDeviceId(deviceId);
        bindDO.setFarmId(moduleDO.getFarmId());
        bindDO.setPondId(moduleDO.getPondId());
        bindDO.setOwnerTenantId(moduleDO.getTenantId());
        bindDO.setCreateTime(LocalDateTime.now());
        bindDO.setUpdateTime(LocalDateTime.now());
        moduleDeviceMapper.insert(bindDO);
    }

    @Override
    public void unbindDevice(Long moduleId, Long deviceId) {
        YModuleDO moduleDO = validateModuleExists(moduleId);
        // 权限：只能解绑自己的（或自己子租户的）模块
        EiotTenantUtils.validateTenantPermission(moduleDO.getTenantId(), tenantApi,
                ErrorCodeConstants.MODULE_PERMISSION_DENIED);
        LambdaQueryWrapperX<YModuleDeviceDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(YModuleDeviceDO::getModuleId, moduleId)
                .eq(YModuleDeviceDO::getDeviceId, deviceId);
        moduleDeviceMapper.delete(wrapper);
    }

    @Override
    @TenantIgnore
    public List<YModuleDeviceRespVO> getModuleDevices(Long moduleId) {
        List<YModuleDeviceDO> binds = moduleDeviceMapper.selectList(
                YModuleDeviceDO::getModuleId, moduleId);
        if (binds.isEmpty()) {
            return List.of();
        }
        List<Long> deviceIds = binds.stream()
                .map(YModuleDeviceDO::getDeviceId)
                .collect(Collectors.toList());
        List<EiotDeviceInfoDO> devices = deviceInfoMapper.selectList(
                EiotDeviceInfoDO::getId, deviceIds);
        return binds.stream().map(bind -> {
            YModuleDeviceRespVO vo = new YModuleDeviceRespVO();
            vo.setId(bind.getId());
            vo.setDeviceId(bind.getDeviceId());
            vo.setBindAt(bind.getCreateTime() != null
                    ? bind.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    : null);
            // 查找设备信息填充 deviceCode/deviceName/deviceType
            devices.stream()
                    .filter(d -> d.getId().equals(bind.getDeviceId()))
                    .findFirst()
                    .ifPresent(d -> {
                        vo.setDeviceCode(d.getDn());        // DN 作为 deviceCode
                        vo.setDeviceName(d.getName());       // 设备名
                        vo.setDeviceType(d.getProductKey()); // productKey 作为 deviceType
                    });
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @TenantIgnore
    public List<YModuleSimpleRespVO> getModuleListByPondId(Long pondId) {
        // 模块已通过 pondId 关联到塘口，塘口层面已有租户权限控制，此处无需再过滤 tenant_id模块管理详情页面 创建人更新人 显示的是数字
        //14:36
        //CodeBuddy
        //深度思考
        //Task code-explorer subagent: 查找模块管理详情页面代码
        //在 /Users/weitianyuan/yuange/project/shihe/enjoy-iot 项目中，查找与"模块管理"(module)相关的详情页面和接口代码。具体需要：
        //
        //在 enjoy-web/src/views 中搜索与 module 相关的详情/编辑页面文件（如 detail.vue, edit.vue, form.vue 等）
        //在 enjoy-web/src/api/eiot 中搜索 module 相关的 API 文件
        //在后端 module-eiot 中搜索 ModuleController 或类似的 controller
        //重点关注：
        //
        //详情页面如何显示 creator/updater（创建人/更新人）字段
        //后端接口返回的 creator/updater 是什么类型（Long ID 还是 String 名称）
        //列出所有找到的完整文件路径和关键代码片段。
        //
        //Execution Summary: 22 tool uses, cost: 72.96s, credits: 3.73
        //深度思考
        //读取
        //YModuleServiceImpl.java
        //L1-50
        //搜索
        //getModule\b
        //深度思考
        //读取
        //YModuleServiceImpl.java
        //L126-175
        //深度思考
        //搜索
        //enrichSingleModuleVO
        //深度思考
        //搜索
        //loadModuleUserNameMap
        //深度思考
        //读取
        //YModuleServiceImpl.java
        //L148-207
        //读取
        //YModuleServiceImpl.java
        //L467-486
        //深度思考
        //搜索
        //interface AdminUserApi
        //搜索
        //getUserList
        //深度思考
        //读取
        //AdminUserApi.java
        //L1-End
        //深度思考
        //问题找到了。getModule 方法上标注了 @TenantIgnore，导致调用 adminUserApi.getUserList() 时租户上下文被清除，RPC 调用失败（异常被静默捕获），最终降级显示了原始的用户 ID 数字。
        //
        //同时，列表页的 enrichModuleRespVOListFromJoin 方法也完全没有处理 creator/updater 的转换。
        //
        //让我修复这两个问题：
        //
        //读取
        //YModuleServiceImpl.java
        //L354-383
        //深度思考
        //YModuleServiceImpl.java
        //(修改)
        //+7
        //-2
        //查看文件
        //深度思考
        //YModuleServiceImpl.java
        //(修改)
        //+27
        //-1
        //查看文件
        //深度思考
        //在 YModuleServiceImpl.java 中未发现 linter 错误
        //深度思考
        //没有 linter 错误。总结修改：
        //
        //修复内容
        //根因：getModule 方法上有 @TenantIgnore 注解，清除了租户上下文。当 enrichSingleModuleVO → loadModuleUserNameMap → adminUserApi.getUserList() 发起 RPC 调用时，缺少租户上下文导致调用失败，异常被静默捕获后降级返回了原始的用户 ID 数字。
        //
        //修复 1（详情页）：getModule 中调用 enrichSingleModuleVO 时，用 TenantUtils.execute(tenantId, ...) 包裹，确保调用 adminUserApi 时有正确的租户上下文。
        //
        //修复 2（列表页）：enrichModuleRespVOListFromJoin 之前完全没有处理 creator/updater 的转换，新增 loadJoinUserNameMap 方法从 VO 列表中提取用户 ID 并批量查询用户名称，填充到列表数据中。
        LambdaQueryWrapperX<YModuleDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(YModuleDO::getPondId, pondId);
        wrapper.orderByDesc(YModuleDO::getCreateTime);
        List<YModuleDO> list = moduleMapper.selectList(wrapper);
        return BeanUtils.toBean(list, YModuleSimpleRespVO.class);
    }

    @Override
    @TenantIgnore  // 设备表有租户隔离，需跨租户查询所有设备
    public List<EiotDeviceInfoDO> getUnboundDeviceList() {
        // NOT EXISTS 子查询：直接从数据库层面判断设备是否已被绑定，按创建时间倒序（最新在前）
        QueryWrapper<EiotDeviceInfoDO> wrapper = new QueryWrapper<>();
        wrapper.notExists("SELECT 1 FROM y_module_device WHERE y_module_device.device_id = eiot_device_info.id");
        wrapper.orderByDesc("create_time");
        return deviceInfoMapper.selectList(wrapper);
    }

    // ========== 私有方法 ==========

    private String generateModuleCode() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = RandomStringUtils.randomNumeric(5);
        return "MOD-" + dateStr + "-" + random;
    }

    private YModuleDO validateModuleExists(Long id) {
        YModuleDO moduleDO = moduleMapper.selectById(id);
        if (moduleDO == null) {
            throw exception(ErrorCodeConstants.MODULE_NOT_EXISTS);
        }
        return moduleDO;
    }

    private YFarmDO validateFarmExists(Long farmId) {
        YFarmDO farmDO = farmMapper.selectById(farmId);
        if (farmDO == null) {
            throw exception(ErrorCodeConstants.FARM_NOT_EXISTS);
        }
        return farmDO;
    }

    private YPondDO validatePondExists(Long pondId) {
        YPondDO pondDO = pondMapper.selectById(pondId);
        if (pondDO == null) {
            throw exception(ErrorCodeConstants.POND_NOT_EXISTS);
        }
        return pondDO;
    }

    private void validateModuleCodeUnique(String moduleCode, Long excludeId) {
        if (StrUtil.isBlank(moduleCode)) return;
        LambdaQueryWrapperX<YModuleDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(YModuleDO::getModuleCode, moduleCode);
        if (excludeId != null) {
            wrapper.ne(YModuleDO::getId, excludeId);
        }
        if (moduleMapper.selectCount(wrapper) > 0) {
            throw exception(ErrorCodeConstants.MODULE_CODE_DUPLICATE);
        }
    }

    // 注：原 validatePlatformPermission()（要求 tenantId == 1）已移除，
    // 改为 EiotTenantUtils.validateTenantPermission(...) 以支持普通租户管理员操作。

    private LocalDate parseDate(String dateStr) {
        if (StrUtil.isBlank(dateStr)) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 批量填充审计字段、关联名称和租户名称（从 DO 列表）
     */
    private void enrichModuleRespVOList(List<YModuleRespVO> voList, List<YModuleDO> doList) {
        Map<Long, String> tenantNameMap = loadTenantNameMap();
        Map<Long, String> farmNameMap = loadFarmNameMap(doList);
        Map<Long, String> pondNameMap = loadPondNameMap(doList);
        Map<Long, String> userNameMap = loadModuleUserNameMap(doList);
        for (int i = 0; i < voList.size(); i++) {
            YModuleRespVO vo = voList.get(i);
            YModuleDO d = doList.get(i);
            vo.setCreateTime(d.getCreateTime());
            vo.setUpdateTime(d.getUpdateTime());
            vo.setCreator(userNameMap.getOrDefault(parseUserId(d.getCreator()), d.getCreator()));
            vo.setUpdater(userNameMap.getOrDefault(parseUserId(d.getUpdater()), d.getUpdater()));
            vo.setFarmName(farmNameMap.getOrDefault(vo.getFarmId(), ""));
            vo.setPondName(pondNameMap.getOrDefault(vo.getPondId(), ""));
            vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
        }
    }

    /**
     * 批量填充关联名称和租户名称（连表查询后，从 VO 自身获取 farmId/pondId/tenantId）
     */
    private void enrichModuleRespVOListFromJoin(List<YModuleRespVO> voList) {
        if (voList.isEmpty()) return;
        Map<Long, String> tenantNameMap = loadTenantNameMap();
        // 从 VO 收集 farmId/pondId 及 userId
        List<Long> farmIds = voList.stream().map(YModuleRespVO::getFarmId).distinct().collect(Collectors.toList());
        List<Long> pondIds = voList.stream().map(YModuleRespVO::getPondId).distinct().collect(Collectors.toList());
        Map<Long, String> farmNameMap = loadFarmNameMapByIds(farmIds);
        Map<Long, String> pondNameMap = loadPondNameMapByIds(pondIds);
        Map<Long, String> userNameMap = loadJoinUserNameMap(voList);
        for (YModuleRespVO vo : voList) {
            vo.setFarmName(farmNameMap.getOrDefault(vo.getFarmId(), ""));
            vo.setPondName(pondNameMap.getOrDefault(vo.getPondId(), ""));
            vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
            vo.setCreator(userNameMap.getOrDefault(parseUserId(vo.getCreator()), vo.getCreator()));
            vo.setUpdater(userNameMap.getOrDefault(parseUserId(vo.getUpdater()), vo.getUpdater()));
        }
    }

    /**
     * 从连表查询结果的 VO 列表中提取 userId，加载用户名称映射
     */
    private Map<Long, String> loadJoinUserNameMap(List<YModuleRespVO> voList) {
        List<Long> userIds = voList.stream()
                .flatMap(vo -> Stream.of(vo.getCreator(), vo.getUpdater()))
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

    private void enrichSingleModuleVO(YModuleRespVO vo, YModuleDO moduleDO) {
        vo.setCreateTime(moduleDO.getCreateTime());
        vo.setUpdateTime(moduleDO.getUpdateTime());
        Map<Long, String> userNameMap = loadModuleUserNameMap(List.of(moduleDO));
        vo.setCreator(userNameMap.getOrDefault(parseUserId(moduleDO.getCreator()), moduleDO.getCreator()));
        vo.setUpdater(userNameMap.getOrDefault(parseUserId(moduleDO.getUpdater()), moduleDO.getUpdater()));
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
    private Map<Long, String> loadFarmNameMap(List<YModuleDO> doList) {
        List<Long> farmIds = doList.stream()
                .map(YModuleDO::getFarmId)
                .distinct()
                .collect(Collectors.toList());
        return loadFarmNameMapByIds(farmIds);
    }

    private Map<Long, String> loadFarmNameMapByIds(List<Long> farmIds) {
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
     * 批量加载 pondId → pondName 映射
     */
    private Map<Long, String> loadPondNameMap(List<YModuleDO> doList) {
        List<Long> pondIds = doList.stream()
                .map(YModuleDO::getPondId)
                .distinct()
                .collect(Collectors.toList());
        return loadPondNameMapByIds(pondIds);
    }

    private Map<Long, String> loadPondNameMapByIds(List<Long> pondIds) {
        if (pondIds.isEmpty()) return Map.of();
        try {
            List<YPondDO> ponds = pondMapper.selectList(
                    new LambdaQueryWrapperX<YPondDO>().in(YPondDO::getId, pondIds));
            return ponds.stream().collect(Collectors.toMap(
                    YPondDO::getId,
                    YPondDO::getPondName,
                    (a, b) -> a));
        } catch (Exception e) {
            log.warn("加载塘口名称失败", e);
            return Map.of();
        }
    }

    /**
     * 批量加载用户 ID → 用户昵称的映射
     */
    private Map<Long, String> loadModuleUserNameMap(List<YModuleDO> doList) {
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
}
