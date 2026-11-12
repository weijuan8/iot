
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
package com.enjoyiot.module.eiot.service.alert;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enjoyiot.framework.common.exception.util.ServiceExceptionUtil;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.framework.common.util.object.BeanUtils;
import com.enjoyiot.module.eiot.api.alert.dto.AlertConfig;
import com.enjoyiot.module.eiot.api.alert.dto.AlertConfigPageReqVO;
import com.enjoyiot.module.eiot.api.alert.dto.AlertRecord;
import com.enjoyiot.module.eiot.api.alert.dto.Message;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.module.eiot.controller.admin.alertconfig.vo.AlertConfigSaveReqVO;
import com.enjoyiot.module.eiot.controller.admin.alertconfig.vo.AlertRecordPageReq;
import com.enjoyiot.module.eiot.convert.AlertConfigConvert;
import com.enjoyiot.module.eiot.convert.AlertRecordConvert;
import com.enjoyiot.module.eiot.dal.dataobject.AlertRecordDO;
import com.enjoyiot.module.eiot.dal.dataobject.alertconfig.AlertConfigDO;
import com.enjoyiot.module.eiot.dal.mysql.AlertRecordMapper;
import com.enjoyiot.module.eiot.dal.mysql.alertconfig.AlertConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 【告警配置 Service】— CRUD + 告警记录写入
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>告警配置 CRUD: 创建/修改/删除/分页查询</li>
 *   <li>告警记录写入: addAlertRecord() — 由 AlertMessageEventListener 调用</li>
 * </ul>
 *
 * <h2>告警记录写入</h2>
 * 从规则引擎的 AlertMessageEventListener 收到消息事件后：
 * <ol>
 *   <li>查告警配置获取 name + level</li>
 *   <li>组装 AlertRecord (name, level, alertTime, details=模板渲染内容, readFlg=false)</li>
 *   <li>INSERT INTO eiot_alert_record</li>
 * </ol>
 *
 * <h2>告警配置与规则的关系</h2>
 * 告警配置通过 ruleInfoId 绑定到规则引擎规则。
 * 规则启动时通过 AlertApi.getAlertConfigPage() 读取所有启用配置，
 * 每个配置生成一个 AlertService 实例。
 *
 * @see AlertMessageEventListener 调用方（写告警记录）
 * @see com.enjoyiot.eiot.ruleengine.action.alert.AlertService 规则引擎告警执行
 */
@Service
@Validated
public class AlertConfigServiceImpl implements AlertConfigService {

    @Resource
    private AlertConfigMapper alertConfigMapper;

    @Resource
    private AlertRecordMapper alertRecordMapper;

    @Override
    public Long createAlertConfig(AlertConfigSaveReqVO createReqVO) {
        // 插入
        AlertConfigDO alertConfig = BeanUtils.toBean(createReqVO, AlertConfigDO.class);
        alertConfigMapper.insert(alertConfig);
        // 返回
        return alertConfig.getId();
    }

    @Override
    public void updateAlertConfig(AlertConfigSaveReqVO updateReqVO) {
        // 校验存在
        validateAlertConfigExists(updateReqVO.getId());
        // 更新
        AlertConfigDO updateObj = BeanUtils.toBean(updateReqVO, AlertConfigDO.class);
        alertConfigMapper.updateById(updateObj);
    }

    @Override
    public void deleteAlertConfig(Long id) {
        // 校验存在
        validateAlertConfigExists(id);
        // 删除
        alertConfigMapper.deleteById(id);
    }

    @Override
    public AlertConfig getAlertConfig(Long id) {
        return AlertConfigConvert.INSTANCE.convert(alertConfigMapper.selectById(id));
    }

    @Override
    public PageResult<AlertConfig> getAlertConfigPage(AlertConfigPageReqVO pageReqVO) {
        return AlertConfigConvert.INSTANCE.convertPage(alertConfigMapper.selectPage(pageReqVO));
    }

    public PageResult<AlertRecord> selectAlertRecordPage(AlertRecordPageReq request) {
        Long tenantId = TenantContextHolder.getTenantId();
        String name = (request.getName() != null && !request.getName().isEmpty()) ? request.getName() : null;
        String level = (request.getLevel() != null && !request.getLevel().isEmpty()) ? request.getLevel() : null;
        Boolean readFlg = request.getReadFlg();
        Page<AlertRecordDO> page = new Page<>(request.getPageNo(), request.getPageSize());
        page = alertRecordMapper.selectPageWithJoin(page, tenantId, null, null, null, level, name, readFlg);
        return new PageResult<>(AlertRecordConvert.INSTANCE.convertList(page.getRecords()), page.getTotal());
    }

    /**
     * 【写告警记录】— 规则触发告警后调用
     *
     * <p>写入 eiot_alert_record 表，用户在前端告警列表可见。
     *
     * @param config  告警配置（含 name, level）
     * @param content 模板渲染后的格式化内容（告警详情）
     */
    public void addAlertRecord(AlertConfig config, String content) {
        AlertRecord record = AlertRecord.builder()
                .level(config.getLevel())
                .name(config.getName())
                .readFlg(false)
                .alertTime(System.currentTimeMillis())
                .details(content).tenantId(config.getTenantId())
                .build();

        if (record.getId() == null) {
            alertRecordMapper.insert(AlertRecordConvert.INSTANCE.convertDO(record));
        } else {
            alertRecordMapper.updateById(AlertRecordConvert.INSTANCE.convertDO(record));
        }
    }

    @Override
    public void addThresholdAlertRecord(Message message) {
        Map<String, Object> param = message.getParam() != null ? message.getParam() : new HashMap<>();
        String level = (String) param.getOrDefault("level", "一般");
        String propName = (String) param.getOrDefault("propName", "");
        String pondName = (String) param.getOrDefault("pondName", "");
        String alertType = (String) param.getOrDefault("alertType", "");
        String name = (pondName + propName + alertType).trim();
        if (name.isEmpty()) name = "阈值告警";

        AlertRecord record = AlertRecord.builder()
                .level(level)
                .name(name)
                .readFlg(false)
                .alertTime(System.currentTimeMillis())
                .details(message.getFormatContent())
                .tenantId(message.getTenantId())
                .deviceId(toLong(param.get("deviceId")))
                .farmId(toLong(param.get("farmId")))
                .pondId(toLong(param.get("pondId")))
                .moduleId(toLong(param.get("moduleId")))
                // 阈值告警明细：当前值、预警阈值、比较符、单位、属性标识符/名称、告警类型
                .value(toDouble(param.get("value")))
                .threshold(toDouble(param.get("threshold")))
                .cond((String) param.get("condition"))
                .unit((String) param.get("unit"))
                .propKey((String) param.get("propKey"))
                .propName(propName)
                .alertType(alertType)
                .build();
        alertRecordMapper.insert(AlertRecordConvert.INSTANCE.convertDO(record));
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Double) return (Double) v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private void validateAlertConfigExists(Long id) {
        if (alertConfigMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.NOT_EXISTS);
        }
    }

}
