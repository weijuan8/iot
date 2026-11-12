package com.enjoyiot.module.eiot.service.farm;

import cn.hutool.core.util.StrUtil;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.framework.common.util.object.BeanUtils;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import com.enjoyiot.module.eiot.controller.admin.farm.vo.*;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YFarmDO;
import com.enjoyiot.module.eiot.dal.dataobject.farm.YPondDO;
import com.enjoyiot.module.eiot.dal.mysql.farm.YFarmMapper;
import com.enjoyiot.module.eiot.dal.mysql.farm.YPondMapper;
import com.enjoyiot.module.eiot.util.EiotTenantUtils;
import com.enjoyiot.module.system.api.tenant.TenantApi;
import com.enjoyiot.module.system.api.tenant.dto.TenantSimpleRespDTO;
import com.enjoyiot.module.system.api.user.AdminUserApi;
import com.enjoyiot.module.system.api.user.dto.AdminUserRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 养殖场 Service 实现类
 *
 * @author EnjoyIot
 */
@Service
@Validated
@Slf4j
public class YFarmServiceImpl implements YFarmService {

    @Resource
    private YFarmMapper farmMapper;

    @Resource
    private YPondMapper pondMapper;

    @Resource
    private TenantApi tenantApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    @TenantIgnore // 绕过自动租户过滤，使 validateFarmCodeUnique 能跨租户校验编码唯一性
    public Long createFarm(YFarmSaveReqVO createReqVO) {
        // 权限：平台管理员可操作任意租户；普通租户管理员只能操作自己 + 自己的一级子租户
        EiotTenantUtils.validateTenantPermission(createReqVO.getTenantId(), tenantApi,
                ErrorCodeConstants.FARM_PERMISSION_DENIED);
        // 自动生成场编码（如果前端没传）
        if (StrUtil.isBlank(createReqVO.getFarmCode())) {
            createReqVO.setFarmCode(generateFarmCode());
        }
        validateFarmCodeUnique(createReqVO.getFarmCode(), null);
        YFarmDO farmDO = BeanUtils.toBean(createReqVO, YFarmDO.class);
        // 使用前端指定的租户ID写入数据，确保养殖场归属于正确的租户
        TenantUtils.execute(createReqVO.getTenantId(), () -> {
            farmMapper.insert(farmDO);
        });
        return farmDO.getId();
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，使 validateFarmExists 能跨租户查询
    public void updateFarm(YFarmSaveReqVO updateReqVO) {
        YFarmDO existingFarm = validateFarmExists(updateReqVO.getId());
        // 权限校验：未传 tenantId 时沿用养殖场原有的所属租户
        EiotTenantUtils.validateTenantPermission(
                updateReqVO.getTenantId() != null ? updateReqVO.getTenantId() : existingFarm.getTenantId(),
                tenantApi, ErrorCodeConstants.FARM_PERMISSION_DENIED);
        if (StrUtil.isNotBlank(updateReqVO.getFarmCode())) {
            validateFarmCodeUnique(updateReqVO.getFarmCode(), updateReqVO.getId());
        }
        YFarmDO updateObj = BeanUtils.toBean(updateReqVO, YFarmDO.class);
        // 使用养殖场所属租户的上下文执行更新，避免 MyBatis 拦截器注入错误的 tenant_id
        Long targetTenantId = updateReqVO.getTenantId() != null ? updateReqVO.getTenantId() : existingFarm.getTenantId();
        TenantUtils.execute(targetTenantId, () -> {
            farmMapper.updateById(updateObj);
        });
    }

    @Override
    @TenantIgnore // 绕过自动租户过滤，使 validateFarmExists 能跨租户查询
    public void deleteFarm(Long id) {
        YFarmDO existingFarm = validateFarmExists(id);
        // 权限校验：只能删除自己（或自己子租户）名下的养殖场
        EiotTenantUtils.validateTenantPermission(existingFarm.getTenantId(), tenantApi,
                ErrorCodeConstants.FARM_PERMISSION_DENIED);
        // 使用养殖场所属租户的上下文执行删除，避免 MyBatis 拦截器注入错误的 tenant_id
        TenantUtils.execute(existingFarm.getTenantId(), () -> {
            farmMapper.deleteById(id);
        });
    }

    @Override
    @TenantIgnore
    public YFarmRespVO getFarm(Long id) {
        YFarmDO farmDO = validateFarmExists(id);
        return convertToRespVO(farmDO);
    }

    @Override
    @TenantIgnore
    public PageResult<YFarmRespVO> getFarmPage(YFarmPageReqVO pageReqVO) {
        LambdaQueryWrapperX<YFarmDO> wrapper = new LambdaQueryWrapperX<>();
        Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
        if (!EiotTenantUtils.isPlatformAdmin(currentTenantId)) {
            wrapper.eq(YFarmDO::getTenantId, currentTenantId);
        } else if (pageReqVO.getTenantId() != null) {
            wrapper.eq(YFarmDO::getTenantId, pageReqVO.getTenantId());
        }
        if (StrUtil.isNotBlank(pageReqVO.getFarmName())) {
            wrapper.like(YFarmDO::getFarmName, pageReqVO.getFarmName());
        }
        if (StrUtil.isNotBlank(pageReqVO.getFarmType())) {
            wrapper.eq(YFarmDO::getFarmType, pageReqVO.getFarmType());
        }
        wrapper.orderByDesc(YFarmDO::getCreateTime);
        PageResult<YFarmDO> pageResult = farmMapper.selectPage(pageReqVO, wrapper);
        List<YFarmDO> doList = pageResult.getList();
        List<YFarmRespVO> voList = BeanUtils.toBean(doList, YFarmRespVO.class);
        // 填充审计字段（createTime→createdAt, creator→createdBy）和租户名称
        enrichFarmRespVOList(voList, doList);
        return new PageResult<>(voList, pageResult.getTotal());
    }

    @Override
    @TenantIgnore
    public List<YFarmSimpleRespVO> getFarmSimpleList(Long tenantId) {
        LambdaQueryWrapperX<YFarmDO> wrapper = new LambdaQueryWrapperX<>();
        if (tenantId != null) {
            // 明确传了租户ID，按该租户过滤
            wrapper.eq(YFarmDO::getTenantId, tenantId);
        } else {
            Long currentTenantId = EiotTenantUtils.getCurrentTenantId();
            if (!EiotTenantUtils.isPlatformAdmin(currentTenantId)) {
                // 普通租户：只看自己租户的养殖场
                wrapper.eq(YFarmDO::getTenantId, currentTenantId);
            }
            // 平台管理员且未传 tenantId：看全部
        }
        wrapper.orderByDesc(YFarmDO::getCreateTime);
        List<YFarmDO> list = farmMapper.selectList(wrapper);
        List<YFarmSimpleRespVO> voList = BeanUtils.toBean(list, YFarmSimpleRespVO.class);
        // 填充租户名称
        Map<Long, String> tenantNameMap = loadTenantNameMap();
        for (YFarmSimpleRespVO vo : voList) {
            vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
        }
        return voList;
    }

    // ========== 私有方法 ==========

    private YFarmRespVO convertToRespVO(YFarmDO farmDO) {
        YFarmRespVO vo = BeanUtils.toBean(farmDO, YFarmRespVO.class);
        enrichSingleFarmVO(vo, farmDO);
        return vo;
    }

    /**
     * 批量填充审计字段（createTime→createdAt, creator→createdBy, updateTime→updatedAt, updater→updatedBy）、
     * 租户名称、以及通过 count 统计的塘口数量
     */
    private void enrichFarmRespVOList(List<YFarmRespVO> voList, List<YFarmDO> doList) {
        Map<Long, String> tenantNameMap = loadTenantNameMap();
        Map<Long, Long> pondCountMap = loadPondCountMap(doList);
        Map<Long, String> userNameMap = loadUserNameMap(doList);
        for (int i = 0; i < voList.size(); i++) {
            YFarmRespVO vo = voList.get(i);
            YFarmDO d = doList.get(i);
            vo.setCreatedAt(d.getCreateTime());
            vo.setUpdatedAt(d.getUpdateTime());
            vo.setCreatedBy(userNameMap.getOrDefault(parseUserId(d.getCreator()), d.getCreator()));
            vo.setUpdatedBy(userNameMap.getOrDefault(parseUserId(d.getUpdater()), d.getUpdater()));
            vo.setPondCount(pondCountMap.getOrDefault(vo.getId(), 0L).intValue());
            vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
        }
    }

    private void enrichSingleFarmVO(YFarmRespVO vo, YFarmDO farmDO) {
        vo.setCreatedAt(farmDO.getCreateTime());
        vo.setUpdatedAt(farmDO.getUpdateTime());
        // 创建人/更新人：将 ID 转为用户昵称
        Map<Long, String> userNameMap = loadUserNameMap(List.of(farmDO));
        vo.setCreatedBy(userNameMap.getOrDefault(parseUserId(farmDO.getCreator()), farmDO.getCreator()));
        vo.setUpdatedBy(userNameMap.getOrDefault(parseUserId(farmDO.getUpdater()), farmDO.getUpdater()));
        // 塘口数量通过 count 统计
        Long count = pondMapper.selectCount(
                new LambdaQueryWrapperX<YPondDO>().eq(YPondDO::getFarmId, farmDO.getId()));
        vo.setPondCount(count.intValue());
        // 租户名称
        Map<Long, String> tenantNameMap = loadTenantNameMap();
        vo.setTenantName(tenantNameMap.getOrDefault(vo.getTenantId(), ""));
    }

    /**
     * 批量加载租户 ID → 租户名称的映射
     */
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
     * 批量统计每个养殖场的塘口数量
     */
    private Map<Long, Long> loadPondCountMap(List<YFarmDO> doList) {
        List<Long> farmIds = doList.stream()
                .map(YFarmDO::getId)
                .distinct()
                .collect(Collectors.toList());
        if (farmIds.isEmpty()) return Map.of();
        try {
            List<YPondDO> ponds = pondMapper.selectList(
                    new LambdaQueryWrapperX<YPondDO>().in(YPondDO::getFarmId, farmIds));
            return ponds.stream().collect(Collectors.groupingBy(
                    YPondDO::getFarmId, Collectors.counting()));
        } catch (Exception e) {
            log.warn("统计塘口数量失败", e);
            return Map.of();
        }
    }

    /**
     * 批量加载用户 ID → 用户昵称的映射
     */
    private Map<Long, String> loadUserNameMap(List<YFarmDO> doList) {
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

    /**
     * 将 creator/updater 字符串转为 Long 用户ID
     */
    private Long parseUserId(String userIdStr) {
        if (StrUtil.isBlank(userIdStr)) return null;
        try {
            return Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private YFarmDO validateFarmExists(Long id) {
        YFarmDO farmDO = farmMapper.selectById(id);
        if (farmDO == null) {
            throw exception(ErrorCodeConstants.FARM_NOT_EXISTS);
        }
        return farmDO;
    }

    private void validateFarmCodeUnique(String farmCode, Long excludeId) {
        LambdaQueryWrapperX<YFarmDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(YFarmDO::getFarmCode, farmCode);
        if (excludeId != null) {
            wrapper.ne(YFarmDO::getId, excludeId);
        }
        if (farmMapper.selectCount(wrapper) > 0) {
            throw exception(ErrorCodeConstants.FARM_CODE_DUPLICATE);
        }
    }

    /**
     * 生成场编码，格式：FARM-yyyyMMdd-5位随机数
     */
    private String generateFarmCode() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = RandomStringUtils.randomNumeric(5);
        return "FARM-" + dateStr + "-" + random;
    }
}
