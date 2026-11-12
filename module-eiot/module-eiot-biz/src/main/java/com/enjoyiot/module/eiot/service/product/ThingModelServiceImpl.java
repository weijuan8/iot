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
package com.enjoyiot.module.eiot.service.product;

import cn.hutool.core.util.ObjectUtil;
import com.enjoyiot.eiot.IDbStructureData;
import com.enjoyiot.framework.common.exception.ServiceException;
import com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.framework.common.util.object.BeanUtils;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.module.eiot.api.thingmodel.dto.ThingModel;
import com.enjoyiot.module.eiot.controller.admin.product.vo.IotThingModelSaveReqVO;
import com.enjoyiot.module.eiot.controller.admin.thingmodel.vo.ThingModelSaveReqVO;
import com.enjoyiot.module.eiot.convert.ThingModelConvert;
import com.enjoyiot.module.eiot.dal.dataobject.thingmodel.ThingModelDO;
import com.enjoyiot.module.eiot.dal.mysql.thingmodel.ThingModelMapper;
import com.enjoyiot.module.eiot.dal.redis.RedisKeyConstants;
import com.enjoyiot.framework.tenant.core.aop.TenantIgnore;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【物模型服务】— 物模型 CRUD + 自动同步 TDengine 表结构
 *
 * <h2>职责</h2>
 * 物模型是设备数据的"词典"：定义产品有哪些属性，每个属性是什么类型。
 * 本服务在保存物模型时自动同步 TDengine 数据库的表结构。
 *
 * <h2>两条核心链路</h2>
 * <ol>
 *   <li>首次保存 → defineThingModel() → CREATE STABLE</li>
 *   <li>修改保存 → updateThingModel() → ALTER STABLE (ADD/MODIFY/DROP COLUMN)</li>
 * </ol>
 *
 * <h2>缓存策略</h2>
 * <ul>
 *   <li>读取: @Cacheable → Redis 缓存（首次读MySQL，后续读Redis）</li>
 *   <li>保存: @CacheEvict → 清除 Redis 缓存，下次读取重新从 MySQL 加载</li>
 * </ul>
 *
 * @see DbStructureDataImpl TDengine DDL 执行
 * @see FieldParser 物模型属性 → TDengine 字段转换
 * @see DevicePropertyHandler 上报数据时的物模型白名单过滤
 */
@Service
@Validated
public class ThingModelServiceImpl implements ThingModelService {

    @Resource
    private ThingModelMapper thingModelMapper;    // MySQL 持久化

    @Resource
    private IDbStructureData dbStructureData;     // TDengine DDL 操作

    @Resource
    private DeviceApi deviceApi;                  // 设备缓存管理

    /**
     * 【保存物模型】— 核心入口
     *
     * <p>触发：后台 PUT /eiot/thing-model/save
     *
     * <p>执行流程:
     * <ol>
     *   <li>查旧物模型（判断是创建还是更新）</li>
     *   <li>解析 JSON → ThingModel 对象</li>
     *   <li>规范化 (normalize): 属性类型标准化（int→int32, double→double...）</li>
     *   <li>校验 (validate): identifier 唯一性、类型合法性、范围合理性</li>
     *   <li>首次创建: defineThingModel() → CREATE STABLE → MySQL INSERT</li>
     *   <li>修改已有: updateThingModel() → ALTER STABLE → MySQL UPDATE → 清Redis缓存</li>
     * </ol>
     *
     * <p>事务注意：TDengine 和 MySQL 不在同一事务中。
     * 如果建表成功但 MySQL 写入失败（或反之），需要手动清理。
     */
    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.THING_MODEL, key = "#updateReqVO.productKey")
    public void saveThingModel(ThingModelSaveReqVO updateReqVO) {
        String productKey = updateReqVO.getProductKey();
        // 【查旧数据】判断是首次创建还是修改
        ThingModel oldData = getThingModelByProductKey(productKey);

        // 【解析 + 规范化 + 校验】
        ThingModel thingModel = new ThingModel(1L, productKey,
                JsonUtils.parseObject(updateReqVO.getModel(), ThingModel.Model.class));
        normalizeThingModel(thingModel);   // 属性类型标准化
        validateThingModel(thingModel);    // 合法性校验

        if (ObjectUtil.isNull(oldData)) {
            // ===== 首次创建 =====
            // ① 先在 TDengine 建超级表
            dbStructureData.defineThingModel(thingModel);
            // ② 再写入 MySQL（持久化物模型定义）
            IotThingModelSaveReqVO updateObj = BeanUtils.toBean(updateReqVO, IotThingModelSaveReqVO.class);
            updateObj.setModel(JsonUtils.toJsonString(thingModel.getModel()));
            thingModelMapper.insert(BeanUtils.toBean(updateObj, ThingModelDO.class));
        } else {
            // ===== 修改已有 =====
            thingModel.setId(oldData.getId());
            // ① 先更新 TDengine 超级表结构（对比差异，ADD/MODIFY/DROP）
            dbStructureData.updateThingModel(thingModel);
            // ② 更新 MySQL 记录
            IotThingModelSaveReqVO updateObj = BeanUtils.toBean(updateReqVO, IotThingModelSaveReqVO.class);
            updateObj.setId(oldData.getId());
            updateObj.setModel(JsonUtils.toJsonString(thingModel.getModel()));
            validateThingModelExists(updateObj.getId());
            thingModelMapper.updateById(BeanUtils.toBean(updateObj, ThingModelDO.class));
            // ③ 清除 Redis 中该产品的设备属性缓存
            //    因为表结构变了，旧的缓存数据列结构可能不匹配
            //    下次数据上报时会重新从 MySQL 加载物模型
            deviceApi.clearPropertiesCache(productKey);
        }
    }

    @Override
    public ThingModel getThingModel(Long id) {
        ThingModelDO thingModelDO = thingModelMapper.selectById(id);
        ThingModel convert = ThingModelConvert.INSTANCE.convert(thingModelDO);

        if (thingModelDO != null && convert != null) {
            convert.setModel(JsonUtils.parseObject(thingModelDO.getModel(), ThingModel.Model.class));
        }
        return convert;
    }

    @Override
    public ThingModel getThingModelByProductKey(String productKey) {
        ThingModelDO thingModelDO = thingModelMapper.selectOne(ThingModelDO::getProductKey, productKey);
        ThingModel convert = ThingModelConvert.INSTANCE.convert(thingModelDO);

        if (thingModelDO != null && convert != null) {
            convert.setModel(JsonUtils.parseObject(thingModelDO.getModel(), ThingModel.Model.class));
        }
        return convert;
    }

    @Override
    @TenantIgnore
    @Cacheable(cacheNames = RedisKeyConstants.THING_MODEL, key = "#productKey", unless = "#result == null")
    public ThingModel getThingModelByProductKeyFromCache(String productKey) {
        return getThingModelByProductKey(productKey);
    }

    private void validateThingModelExists(Long id) {
        if (thingModelMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.NOT_EXISTS);
        }
    }

    private void normalizeThingModel(ThingModel thingModel) {
        if (thingModel.getModel() == null) {
            thingModel.setModel(new ThingModel.Model());
        }
        if (thingModel.getModel().getProperties() == null) {
            thingModel.getModel().setProperties(new ArrayList<>());
        }
        if (thingModel.getModel().getServices() == null) {
            thingModel.getModel().setServices(new ArrayList<>());
        }
        if (thingModel.getModel().getEvents() == null) {
            thingModel.getModel().setEvents(new ArrayList<>());
        }
        thingModel.getModel().getProperties().forEach(this::normalizeProperty);
        thingModel.getModel().getServices().forEach(service -> {
            if (service.getInputData() == null) {
                service.setInputData(new ArrayList<>());
            }
            if (service.getOutputData() == null) {
                service.setOutputData(new ArrayList<>());
            }
            service.getInputData().forEach(this::normalizeParameter);
            service.getOutputData().forEach(this::normalizeParameter);
        });
        thingModel.getModel().getEvents().forEach(event -> {
            if (event.getOutputData() == null) {
                event.setOutputData(new ArrayList<>());
            }
            event.getOutputData().forEach(this::normalizeParameter);
        });
    }

    private void normalizeProperty(ThingModel.Property property) {
        if (property.getDataType() != null) {
            normalizeDataType(property.getDataType());
        }
    }

    private void normalizeParameter(ThingModel.Parameter parameter) {
        if (parameter.getDataType() != null) {
            normalizeDataType(parameter.getDataType());
        }
    }

    private void normalizeDataType(ThingModel.DataType dataType) {
        if (dataType == null) {
            return;
        }
        dataType.normalizedType();
        if (ThingModel.DataType.TYPE_OBJECT.equals(dataType.getType())) {
            dataType.getPropertySpecs().forEach(this::normalizeParameter);
        } else if (ThingModel.DataType.TYPE_ARRAY.equals(dataType.getType())) {
            normalizeDataType(dataType.getItemTypeSpec());
        }
    }

    private void validateThingModel(ThingModel thingModel) {
        validateUniqueIdentifiers(thingModel.getModel().getProperties(), "property");
        validateUniqueIdentifiers(thingModel.getModel().getServices(), "service");
        validateUniqueIdentifiers(thingModel.getModel().getEvents(), "event");
        thingModel.getModel().getProperties().forEach(property -> validateProperty(property, "property"));
        thingModel.getModel().getServices().forEach(service -> {
            validateIdentifier("service", service.getIdentifier());
            validateParameters(service.getInputData(), "service[" + service.getIdentifier() + "].inputData");
            validateParameters(service.getOutputData(), "service[" + service.getIdentifier() + "].outputData");
        });
        thingModel.getModel().getEvents().forEach(event -> {
            validateIdentifier("event", event.getIdentifier());
            validateParameters(event.getOutputData(), "event[" + event.getIdentifier() + "].outputData");
        });
    }

    private void validateUniqueIdentifiers(List<?> items, String scope) {
        Set<String> identifiers = new HashSet<>();
        for (Object item : items) {
            String identifier;
            if (item instanceof ThingModel.Property) {
                identifier = ((ThingModel.Property) item).getIdentifier();
            } else if (item instanceof ThingModel.Service) {
                identifier = ((ThingModel.Service) item).getIdentifier();
            } else if (item instanceof ThingModel.Event) {
                identifier = ((ThingModel.Event) item).getIdentifier();
            } else {
                continue;
            }
            if (!identifiers.add(identifier)) {
                throw new ServiceException(400, scope + " identifier duplicated: " + identifier);
            }
        }
    }

    private void validateProperty(ThingModel.Property property, String path) {
        validateIdentifier(path, property.getIdentifier());
        validateDataType(path + "[" + property.getIdentifier() + "]", property.getDataType());
    }

    private void validateParameters(List<ThingModel.Parameter> parameters, String path) {
        Set<String> identifiers = new HashSet<>();
        for (ThingModel.Parameter parameter : parameters) {
            validateIdentifier(path, parameter.getIdentifier());
            if (!identifiers.add(parameter.getIdentifier())) {
                throw new ServiceException(400, path + " duplicated identifier: " + parameter.getIdentifier());
            }
            validateDataType(path + "[" + parameter.getIdentifier() + "]", parameter.getDataType());
        }
    }

    private void validateIdentifier(String scope, String identifier) {
        if (StringUtils.isBlank(identifier)) {
            throw new ServiceException(400, scope + " identifier cannot be blank");
        }
        if (!identifier.matches("^[a-zA-Z][a-zA-Z0-9_:.\\-]*$")) {
            throw new ServiceException(400, scope + " identifier illegal: " + identifier);
        }
    }

    private void validateDataType(String path, ThingModel.DataType dataType) {
        if (dataType == null || StringUtils.isBlank(dataType.getType())) {
            throw new ServiceException(400, path + " dataType is required");
        }
        Map<String, Object> specs = dataType.getSpecMap();
        switch (dataType.normalizedType()) {
            case ThingModel.DataType.TYPE_BOOL:
            case ThingModel.DataType.TYPE_DATE:
            case ThingModel.DataType.TYPE_DATETIME:
            case ThingModel.DataType.TYPE_POSITION:
                return;
            case ThingModel.DataType.TYPE_ENUM:
                if (specs.isEmpty()) {
                    throw new ServiceException(400, path + " enum specs cannot be empty");
                }
                return;
            case ThingModel.DataType.TYPE_STRING:
            case ThingModel.DataType.TYPE_TEXT:
                validatePositiveLength(path, specs);
                return;
            case ThingModel.DataType.TYPE_INT32:
            case ThingModel.DataType.TYPE_INT64:
            case ThingModel.DataType.TYPE_FLOAT:
            case ThingModel.DataType.TYPE_DOUBLE:
                validateNumericRange(path, specs);
                return;
            case ThingModel.DataType.TYPE_OBJECT:
                List<ThingModel.Parameter> fields = dataType.getPropertySpecs();
                if (fields.isEmpty()) {
                    throw new ServiceException(400, path + " object type requires nested properties");
                }
                validateParameters(fields, path + ".properties");
                return;
            case ThingModel.DataType.TYPE_ARRAY:
                ThingModel.DataType itemType = dataType.getItemTypeSpec();
                if (itemType == null) {
                    throw new ServiceException(400, path + " array type requires itemType");
                }
                validateDataType(path + ".items", itemType);
                return;
            default:
                throw new ServiceException(400, path + " unsupported dataType: " + dataType.getType());
        }
    }

    private void validatePositiveLength(String path, Map<String, Object> specs) {
        Object length = specs.get("length");
        if (length == null || StringUtils.isBlank(String.valueOf(length))) {
            return;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(length));
            if (parsed <= 0) {
                throw new ServiceException(400, path + " length must be positive");
            }
        } catch (NumberFormatException ex) {
            throw new ServiceException(400, path + " length must be numeric");
        }
    }

    private void validateNumericRange(String path, Map<String, Object> specs) {
        BigDecimal min = toBigDecimal(specs.get("min"), path + ".min");
        BigDecimal max = toBigDecimal(specs.get("max"), path + ".max");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new ServiceException(400, path + " min cannot exceed max");
        }
    }

    private BigDecimal toBigDecimal(Object value, String field) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new ServiceException(400, field + " must be numeric");
        }
    }
}
