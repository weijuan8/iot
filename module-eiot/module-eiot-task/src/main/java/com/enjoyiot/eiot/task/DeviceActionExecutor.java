/*
 *
 *  * | Licensed 未经许可不能去掉「Enjoy-iot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2025] [Enjoy-iot]
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
package com.enjoyiot.eiot.task;


import com.enjoyiot.eiot.common.thing.ThingService;
import com.enjoyiot.eiot.common.utils.UniqueIdUtil;
import com.enjoyiot.eiot.task.model.DeviceActionMsg;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.google.common.collect.Maps;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 【设备动作执行器】- 定时任务中的设备控制指令下发
 *
 * 与规则引擎中的 DeviceAction（实时响应事件）不同，DeviceActionExecutor 用于
 * **周期性**或**定时**的设备控制场景。
 *
 * 【典型使用场景】
 *  ┌─────────────────────┬───────────────────────────────────────┐
 *  │ 场景                 │ 说明                                   │
 *  ├─────────────────────┼───────────────────────────────────────┤
 *  │ 定时开关灯           │ 每天 18:00 打开路灯，06:00 关闭        │
 *  │ 周期巡检             │ 每小时执行一次设备自检（服务调用）      │
 *  │ 定时灌溉             │ 每天 08:00 启动灌溉系统（属性设置）     │
 *  │ 定时固件检查         │ 每天凌晨检查固件版本并推送升级通知       │
 *  └─────────────────────┴───────────────────────────────────────┘
 *
 * 【执行流程】
 *  1. 定时任务触发 → execute(config) 被调用
 *  2. config 被反序列化为 DeviceActionMsg（支持多 service 配置）
 *  3. actionMap 缓存已解析的配置（避免重复反序列化）
 *  4. 遍历 services → invoke(service)
 *  5. 构造 ThingService → DeviceApi.invoke() → 进入标准下行链路
 *
 * 【与 DeviceAction 的区别】
 *  ┌────────────────────┬────────────────────┬───────────────────────┐
 *  │                    │ DeviceAction       │ DeviceActionExecutor  │
 *  ├────────────────────┼────────────────────┼───────────────────────┤
 *  │ 触发方式            │ 设备事件实时触发   │ Cron 定时触发         │
 *  │ 所属模块            │ iot-rule-engine   │ iot-task              │
 *  │ 使用场景            │ 事件→规则→动作联动 │ 周期性设备控制         │
 *  │ 底层调用            │ DeviceApi.invoke()│ DeviceApi.invoke()    │
 *  └────────────────────┴────────────────────┴───────────────────────┘
 *
 * @see DeviceActionMsg.Service 动作配置中的 Service 对象
 */
@Slf4j
@Component
public class DeviceActionExecutor implements ActionExecutor<Object> {

    @Resource
    @org.springframework.context.annotation.Lazy
    private DeviceApi deviceApi;

    /** 动作配置缓存：config.hashCode() → DeviceActionMsg */
    private Map<Integer, DeviceActionMsg> actionMap = Maps.newConcurrentMap();

    @Override
    public String getName() {
        return "device";
    }

    /**
     * 【执行定时设备动作】- 定时任务入口
     * @param config 动作配置 JSON（反序列化为 DeviceActionMsg）
     */
    @Override
    public void execute(String config) {
        if (StringUtils.isBlank(config)) {
            log.error("device executor's config is blank");
            return;
        }
        // 使用 hashCode 缓存已解析的配置对象，避免重复反序列化
        Integer code = config.hashCode();
        DeviceActionMsg action = actionMap.computeIfAbsent(code, k -> JsonUtils.parseObject(config, DeviceActionMsg.class));

        log.info("start device service invoke,{}", JsonUtils.toJsonString(action));
        for (DeviceActionMsg.Service service : action.getServices()) {
            invoke(service);
        }
    }


    /**
     * 【执行单个设备操作】- 构造 ThingService 并通过 DeviceApi 下发
     * 与 DeviceActionService.invoke() 逻辑完全一致，都是：
     * 解析 "pk/dn" → 构造 ThingService → DeviceApi.invoke() → 标准下行链路
     * @param service 设备动作配置中的 Service 对象
     * @return 消息 ID（mid）
     */
    public String invoke(DeviceActionMsg.Service service) {
        String[] pkDn = service.getDevice().split("/");
        ThingService<Map<String, Object>> thingService = new ThingService<>();
        thingService.setMid(UniqueIdUtil.newRequestId());
        thingService.setProductKey(pkDn[0]);
        thingService.setDn(pkDn[1]);
        thingService.setType(service.getType());
        thingService.setIdentifier(service.getIdentifier());
        thingService.setParams(service.parseInputData());
        deviceApi.invoke(thingService);
        return thingService.getMid();
    }
}
