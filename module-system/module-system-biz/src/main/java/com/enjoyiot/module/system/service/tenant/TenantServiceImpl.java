
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
package com.enjoyiot.module.system.service.tenant;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.enjoyiot.framework.common.enums.CommonStatusEnum;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.framework.common.util.collection.CollectionUtils;
import com.enjoyiot.framework.common.util.date.DateUtils;
import com.enjoyiot.framework.common.util.object.BeanUtils;
import com.enjoyiot.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.enjoyiot.framework.tenant.config.TenantProperties;
import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.framework.tenant.core.util.TenantUtils;
import com.enjoyiot.module.system.controller.admin.permission.vo.role.RoleSaveReqVO;
import com.enjoyiot.module.system.controller.admin.tenant.vo.tenant.TenantPageReqVO;
import com.enjoyiot.module.system.api.tenant.dto.TenantSimpleRespDTO;
import com.enjoyiot.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import com.enjoyiot.module.system.convert.tenant.TenantConvert;
import com.enjoyiot.module.system.dal.dataobject.permission.MenuDO;
import com.enjoyiot.module.system.dal.dataobject.permission.RoleDO;
import com.enjoyiot.module.system.dal.dataobject.tenant.TenantDO;
import com.enjoyiot.module.system.dal.dataobject.tenant.TenantPackageDO;
import com.enjoyiot.module.system.dal.mysql.tenant.TenantMapper;
import com.enjoyiot.module.system.enums.permission.RoleCodeEnum;
import com.enjoyiot.module.system.enums.permission.RoleTypeEnum;
import com.enjoyiot.module.system.service.permission.MenuService;
import com.enjoyiot.module.system.service.permission.PermissionService;
import com.enjoyiot.module.system.service.permission.RoleService;
import com.enjoyiot.module.system.service.tenant.handler.TenantInfoHandler;
import com.enjoyiot.module.system.service.tenant.handler.TenantMenuHandler;
import com.enjoyiot.module.system.service.user.AdminUserService;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.enjoyiot.module.system.enums.ErrorCodeConstants.*;
import static java.util.Collections.singleton;




@Service
@Validated
@Slf4j
public class TenantServiceImpl implements TenantService {

    @SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
    @Autowired(required = false) // 由于 platform.tenant.enable 配置项，可以关闭多租户的功能，所以这里只能不强制注入
    private TenantProperties tenantProperties;

    @Resource
    private TenantMapper tenantMapper;

    @Resource
    private TenantPackageService tenantPackageService;
    @Resource
    @Lazy // 延迟，避免循环依赖报错
    private AdminUserService userService;
    @Resource
    private RoleService roleService;
    @Resource
    private MenuService menuService;
    @Resource
    private PermissionService permissionService;

    @Override
    public List<Long> getTenantIdList() {
        List<TenantDO> tenants = tenantMapper.selectList();
        return CollectionUtils.convertList(tenants, TenantDO::getId);
    }

    @Override
    public List<TenantSimpleRespDTO> getTenantList() {
        // 判断当前用户是否为平台管理员（系统租户）
        Long currentTenantId = TenantContextHolder.getTenantId();
        TenantDO currentTenant = currentTenantId != null ? getTenant(currentTenantId) : null;
        boolean isSystemTenant = currentTenant != null && isSystemTenant(currentTenant);

        List<TenantDO> tenants;
        if (isSystemTenant) {
            // 平台管理员：返回所有启用租户
            tenants = tenantMapper.selectList(
                    new LambdaQueryWrapperX<TenantDO>()
                            .eq(TenantDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                            .orderByAsc(TenantDO::getId));
        } else {
            // 普通租户：返回自己的租户 + 自己的一级子租户（不递归，只要一层）
            tenants = tenantMapper.selectList(
                    new LambdaQueryWrapperX<TenantDO>()
                            .and(w -> w
                                    .eq(TenantDO::getId, currentTenantId)
                                    .or()
                                    .eq(TenantDO::getParentId, currentTenantId))
                            .eq(TenantDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                            .orderByAsc(TenantDO::getId));
        }
        return CollectionUtils.convertList(tenants,
                t -> new TenantSimpleRespDTO(t.getId(), t.getName()));
    }

    @Override
    public void validTenant(Long id) {
        TenantDO tenant = getTenant(id);
        if (tenant == null) {
            throw exception(TENANT_NOT_EXISTS);
        }
        if (tenant.getStatus().equals(CommonStatusEnum.DISABLE.getStatus())) {
            throw exception(TENANT_DISABLE, tenant.getName());
        }
        if (DateUtils.isExpired(tenant.getExpireTime())) {
            throw exception(TENANT_EXPIRE, tenant.getName());
        }
    }

    /**
     * 创建租户 — 完整的租户初始化流程
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>校验：租户名不重复、域名不重复、套餐未被禁用</li>
     *   <li>插入租户记录到 system_tenant</li>
     *   <li><b>关键</b>：用 TenantUtils.execute(新租户ID, ...) 切换到新租户上下文</li>
     *   <li>在新租户上下文中：
     *     <ul>
     *       <li>创建角色 role_code=tenant_admin（tenant_id 自动注入）</li>
     *       <li>把套餐的 menuIds 分配给这个角色 → INSERT system_role_menu</li>
     *       <li>创建管理员用户（tenant_id 自动注入）</li>
     *       <li>把角色分配给用户 → INSERT system_user_role</li>
     *     </ul>
     *   </li>
     *   <li>回写租户的 contactUserId</li>
     * </ol>
     *
     * <h3>为什么用 TenantUtils.execute(新租户ID, ...)?</h3>
     * <p>因为 RoleDO 和 AdminUserDO 都继承 TenantBaseDO，
     * MyBatis 拦截器会自动从 TenantContextHolder 取 tenant_id 注入 SQL。
     * 如果不切换上下文，写入的 tenant_id 就是平台管理员自己的租户 ID，导致数据错乱。</p>
     */
    @Override
    @DSTransactional // 多数据源，使用 @DSTransactional 保证本地事务，以及数据源的切换
    public Long createTenant(TenantSaveReqVO createReqVO) {
        // 校验租户名称是否重复
        validTenantNameDuplicate(createReqVO.getName(), null);
        // 校验租户域名是否重复
        validTenantWebsiteDuplicate(createReqVO.getWebsite(), null);
        // 校验套餐是否被禁用
        TenantPackageDO tenantPackage = tenantPackageService.validTenantPackage(createReqVO.getPackageId());

        // 创建租户记录（system_tenant 是全局表，不需要租户上下文）
        TenantDO tenant = BeanUtils.toBean(createReqVO, TenantDO.class);
        // 父租户：未指定时默认取当前登录租户 —— 谁创建，谁就是父租户
        if (tenant.getParentId() == null) {
            tenant.setParentId(TenantContextHolder.getTenantId());
        }
        validTenantParent(tenant.getParentId(), null);
        tenantMapper.insert(tenant);

        // ★ 切换到新租户的上下文，创建租户内部的角色和用户
        // 这样 RoleDO、AdminUserDO 的 tenant_id 会自动注入为新的租户 ID
        TenantUtils.execute(tenant.getId(), () -> {
            // 创建角色（tenant_admin），并分配套餐的菜单权限
            Long roleId = createRole(tenantPackage);
            // 创建管理员用户，并分配角色
            Long userId = createUser(roleId, createReqVO);
            // 回写租户的管理员用户 ID
            tenantMapper.updateById(new TenantDO().setId(tenant.getId()).setContactUserId(userId));
        });
        return tenant.getId();
    }

    /**
     * 为租户创建管理员用户并分配角色
     */
    private Long createUser(Long roleId, TenantSaveReqVO createReqVO) {
        Long userId = userService.createUser(TenantConvert.INSTANCE.convert02(createReqVO));
        // 将 tenant_admin 角色分配给用户 → INSERT system_user_role
        permissionService.assignUserRole(userId, singleton(roleId));
        return userId;
    }

    /**
     * 为租户创建 tenant_admin 角色，并分配套餐的菜单权限
     *
     * <p>这里的 roleService.createRole() 会创建一条 system_role 记录，
     * 因为当前在 TenantUtils.execute(tenantId) 上下文中，
     * RoleDO.tenantId 会被 MyBatis 拦截器自动注入为当前租户 ID。</p>
     */
    private Long createRole(TenantPackageDO tenantPackage) {
        RoleSaveReqVO reqVO = new RoleSaveReqVO();
        reqVO.setName(RoleCodeEnum.TENANT_ADMIN.getName()).setCode(RoleCodeEnum.TENANT_ADMIN.getCode())
                .setSort(0).setRemark("系统自动生成");
        Long roleId = roleService.createRole(reqVO, RoleTypeEnum.SYSTEM.getType());
        // ★ 将套餐的 menuIds 全部赋给角色 → INSERT system_role_menu 批量写入
        permissionService.assignRoleMenu(roleId, tenantPackage.getMenuIds());
        return roleId;
    }

    @Override
    @DSTransactional // 多数据源，使用 @DSTransactional 保证本地事务，以及数据源的切换
    public void updateTenant(TenantSaveReqVO updateReqVO) {
        // 校验存在
        TenantDO tenant = validateUpdateTenant(updateReqVO.getId());
        // 校验租户名称是否重复
        validTenantNameDuplicate(updateReqVO.getName(), updateReqVO.getId());
        // 校验租户域名是否重复
        validTenantWebsiteDuplicate(updateReqVO.getWebsite(), updateReqVO.getId());
        // 校验套餐被禁用
        TenantPackageDO tenantPackage = tenantPackageService.validTenantPackage(updateReqVO.getPackageId());
        // 校验父租户（可为空表示保持原值，不能是自己/自己的子租户）
        validTenantParent(updateReqVO.getParentId(), updateReqVO.getId());

        // 更新租户
        TenantDO updateObj = BeanUtils.toBean(updateReqVO, TenantDO.class);
        // 父租户未传时保持原值，避免被误清空
        if (updateObj.getParentId() == null) {
            updateObj.setParentId(tenant.getParentId());
        }
        tenantMapper.updateById(updateObj);
        // 如果套餐发生变化，则修改其角色的权限
        if (ObjectUtil.notEqual(tenant.getPackageId(), updateReqVO.getPackageId())) {
            updateTenantRoleMenu(tenant.getId(), tenantPackage.getMenuIds());
        }
    }

    /**
     * 校验父租户
     *
     * <p>业务约定只用到一级子租户，但存储上不限制层级；这里只做三项基本校验：
     * 存在性、不能是自己、不能形成环（父租户不能是自己的子孙）。</p>
     *
     * @param parentId 父租户编号；null 表示不指定（新增取当前租户，修改保持原值）
     * @param selfId   当前租户编号；新增时为 null
     */
    private void validTenantParent(Long parentId, Long selfId) {
        if (parentId == null) {
            return;
        }
        // 不能把自己设为父租户
        if (selfId != null && Objects.equals(parentId, selfId)) {
            throw exception(TENANT_PARENT_CAN_NOT_SELF);
        }
        TenantDO parent = tenantMapper.selectById(parentId);
        if (parent == null) {
            throw exception(TENANT_PARENT_NOT_EXISTS);
        }
        // 防环：向上最多追溯几层，父链上不能出现自己
        Long cursor = parent.getParentId();
        for (int i = 0; i < 10 && cursor != null; i++) {
            if (selfId != null && Objects.equals(cursor, selfId)) {
                throw exception(TENANT_PARENT_CIRCULAR);
            }
            TenantDO upper = tenantMapper.selectById(cursor);
            cursor = upper == null ? null : upper.getParentId();
        }
    }

    private void validTenantNameDuplicate(String name, Long id) {
        TenantDO tenant = tenantMapper.selectByName(name);
        if (tenant == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同名字的租户
        if (id == null) {
            throw exception(TENANT_NAME_DUPLICATE, name);
        }
        if (!tenant.getId().equals(id)) {
            throw exception(TENANT_NAME_DUPLICATE, name);
        }
    }

    private void validTenantWebsiteDuplicate(String website, Long id) {
        if (StrUtil.isEmpty(website)) {
            return;
        }
        TenantDO tenant = tenantMapper.selectByWebsite(website);
        if (tenant == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同名字的租户
        if (id == null) {
            throw exception(TENANT_WEBSITE_DUPLICATE, website);
        }
        if (!tenant.getId().equals(id)) {
            throw exception(TENANT_WEBSITE_DUPLICATE, website);
        }
    }

    /**
     * 租户套餐变更时，重新计算租户下所有角色的菜单权限
     *
     * <h3>拆算逻辑</h3>
     * <ol>
     *   <li>切换到租户上下文：TenantUtils.execute(tenantId, ...)</li>
     *   <li>获取该租户下所有角色</li>
     *   <li><b>tenant_admin 角色</b> → 直接用新套餐的 menuIds 全量替换</li>
     *   <li><b>其他自定义角色</b> → 取交集（现有菜单 ∩ 新套餐菜单），
     *       确保不会出现"套餐降级后角色仍有高级菜单"的情况</li>
     * </ol>
     *
     * @param tenantId 租户 ID
     * @param menuIds  新套餐的菜单 ID 集合
     */
    @Override
    @DSTransactional
    public void updateTenantRoleMenu(Long tenantId, Set<Long> menuIds) {
        // 切换到租户上下文，查询该租户的角色
        TenantUtils.execute(tenantId, () -> {
            List<RoleDO> roles = roleService.getRoleList();
            // 兜底校验：所有角色必须属于当前租户
            roles.forEach(role -> Assert.isTrue(tenantId.equals(role.getTenantId()), "角色({}/{}) 租户不匹配",
                    role.getId(), role.getTenantId(), tenantId));
            // 逐个角色重新分配菜单
            roles.forEach(role -> {
                if (Objects.equals(role.getCode(), RoleCodeEnum.TENANT_ADMIN.getCode())) {
                    // ★ tenant_admin：全量替换为新套餐菜单
                    permissionService.assignRoleMenu(role.getId(), menuIds);
                    log.info("[updateTenantRoleMenu][租户管理员({}/{}) 的权限修改为({})]", role.getId(), role.getTenantId(), menuIds);
                    return;
                }
                // ★ 自定义角色：取交集，确保不越权
                Set<Long> roleMenuIds = permissionService.getRoleMenuListByRoleId(role.getId());
                roleMenuIds = CollUtil.intersectionDistinct(roleMenuIds, menuIds);
                permissionService.assignRoleMenu(role.getId(), roleMenuIds);
                log.info("[updateTenantRoleMenu][角色({}/{}) 的权限修改为({})]", role.getId(), role.getTenantId(), roleMenuIds);
            });
        });
    }

    @Override
    public void deleteTenant(Long id) {
        // 校验存在
        validateUpdateTenant(id);
        // 删除
        tenantMapper.deleteById(id);
    }

    private TenantDO validateUpdateTenant(Long id) {
        TenantDO tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw exception(TENANT_NOT_EXISTS);
        }
        // 内置租户，不允许删除
        if (isSystemTenant(tenant)) {
            throw exception(TENANT_CAN_NOT_UPDATE_SYSTEM);
        }
        return tenant;
    }

    @Override
    public TenantDO getTenant(Long id) {
        return tenantMapper.selectById(id);
    }

    @Override
    public PageResult<TenantDO> getTenantPage(TenantPageReqVO pageReqVO) {
        return tenantMapper.selectPage(pageReqVO);
    }

    @Override
    public TenantDO getTenantByName(String name) {
        return tenantMapper.selectByName(name);
    }

    @Override
    public TenantDO getTenantByWebsite(String website) {
        return tenantMapper.selectByWebsite(website);
    }

    @Override
    public Long getTenantCountByPackageId(Long packageId) {
        return tenantMapper.selectCountByPackageId(packageId);
    }

    @Override
    public List<TenantDO> getTenantListByPackageId(Long packageId) {
        return tenantMapper.selectListByPackageId(packageId);
    }

    @Override
    public void handleTenantInfo(TenantInfoHandler handler) {
        // 如果禁用，则不执行逻辑
        if (isTenantDisable()) {
            return;
        }
        // 获得租户
        TenantDO tenant = getTenant(TenantContextHolder.getRequiredTenantId());
        // 执行处理器
        handler.handle(tenant);
    }

    @Override
    public void handleTenantMenu(TenantMenuHandler handler) {
        // 如果禁用，则不执行逻辑
        if (isTenantDisable()) {
            return;
        }
        // 获得租户，然后获得菜单
        TenantDO tenant = getTenant(TenantContextHolder.getRequiredTenantId());
        Set<Long> menuIds;
        if (isSystemTenant(tenant)) { // 系统租户，菜单是全量的
            menuIds = CollectionUtils.convertSet(menuService.getMenuList(), MenuDO::getId);
        } else {
            menuIds = tenantPackageService.getTenantPackage(tenant.getPackageId()).getMenuIds();
        }
        // 执行处理器
        handler.handle(menuIds);
    }

    private static boolean isSystemTenant(TenantDO tenant) {
        return Objects.equals(tenant.getPackageId(), TenantDO.PACKAGE_ID_SYSTEM);
    }

    private boolean isTenantDisable() {
        return tenantProperties == null || Boolean.FALSE.equals(tenantProperties.getEnable());
    }

}
