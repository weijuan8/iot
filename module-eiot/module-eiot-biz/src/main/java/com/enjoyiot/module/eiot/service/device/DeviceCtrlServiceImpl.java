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
package com.enjoyiot.module.eiot.service.device;


import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.virtualdevice.VirtualManager;
import com.enjoyiot.framework.common.exception.ServiceException;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.eiot.api.device.dto.DeviceConfig;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.device.dto.DeviceTopoChangeDTO;
import com.enjoyiot.module.eiot.service.component.ComponentManager;
import com.enjoyiot.module.eiot.service.iot.ParseThingModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * 【设备控制服务实现】- 下行消息（平台 → 设备）的业务核心实现
 *
 * 本类是下行消息链路中最关键的"组装层"，负责：
 * 1. 接收上层（Controller/API）的控制请求
 * 2. 校验设备信息和权限
 * 3. 将控制指令封装为统一的 ThingModelMessage 格式
 * 4. 根据设备类型选择下发通道：
 *    - 虚拟设备（VirtualDevice）→ VirtualManager.send()（脚本引擎模拟响应）
 *    - 真实设备               → ComponentManager.sendToDevice()（消息队列路由）
 *
 * 【send() 方法 - 统一入口】
 * 所有下行操作最终都汇聚到 send() 方法，该方法构造 ThingModelMessage 并选择下发通道。
 *
 * ThingModelMessage 字段映射：
 *  ┌──────────┬────────────────────────────────────────────────┐
 *  │ 操作      │ type → identifier → data                       │
 *  ├──────────┼────────────────────────────────────────────────┤
 *  │ 服务调用  │ "service" → 服务名 → 服务参数 Map               │
 *  │ 属性获取  │ "property" → "get" → 属性名 List               │
 *  │ 属性设置  │ "property" → "set" → 属性键值对 Map             │
 *  │ 配置下发  │ "config" → "set" → 配置键值对 Map              │
 *  │ OTA 升级  │ "ota" → "ota" → OtaData 对象                  │
 *  │ 拓扑变更  │ "topo_change" → "change" → DeviceTopoChangeDTO │
 *  └──────────┴────────────────────────────────────────────────┘
 *
 * @see ComponentManager 组件管理器（真实设备下发入口）
 * @see VirtualManager 虚拟设备管理器（虚拟设备下发入口）
 */
@Slf4j
@Service
public class DeviceCtrlServiceImpl implements DeviceCtrlService {

    @Resource
    private DeviceConfigService deviceConfigService;

    @Resource
    private ParseThingModelService parseThingModelService;

    @Resource
    private DeviceInfoService deviceInfoService;

    @Resource
    private ComponentManager componentManager;

    /** 虚拟设备管理器（Optional，可能不存在） */
    @Resource
    private Optional<VirtualManager> virtualManager;


    /**
     * 【服务调用】- 默认开启权限检查
     */
    @Override
    public void invokeService(Long deviceId, String service,
                              Map<String, Object> args) {
        invokeService(deviceId, service, args, true);
    }

    /**
     * 【服务调用（带权限检查）】
     *
     * 调用链路：
     *  1. getAndCheckDevice() 查询设备信息
     *  2. send() 构造 ThingModelMessage(type="service", identifier=服务名, data=参数Map)
     *  3. ComponentManager.sendToDevice() 或 VirtualManager.send() 下发
     *
     * @param deviceId   设备 ID
     * @param service    服务名称（物模型中定义的服务标识符）
     * @param args       服务参数 Map
     * @param checkOwner 是否校验设备归属权限
     */
    @Override
    /**
     * 【服务调用】- 触发设备执行指定服务
     *
     * <p>将 Controller 传入的参数转为 ThingModelMessage，通过消息队列路由到设备所在组件，
     * 最终由组件通过协议（MQTT/TCP/HTTP）下发给硬件。</p>
     *
     * <h3>参数说明</h3>
     * <table>
     *   <tr><td>deviceId</td><td>设备在 eiot_device_info 表的主键 ID</td></tr>
     *   <tr><td>service</td><td>服务名称，对应物模型中 service 的 identifier，也是 ThingModelMessage 的 identifier 字段</td></tr>
     *   <tr><td>args</td><td>服务参数 Map。GE100D 透传时，key 必须以 raw_ 开头，value 为要下发的命令字符串</td></tr>
     * </table>
     *
     * <h3>构造的消息</h3>
     * ThingModelMessage:
     *   type       = "service"
     *   identifier = service 参数（如 "switch_control"）
     *   data       = args 参数（如 {"raw_": "RELAY1_ON"}）
     *   deviceId   = 设备ID
     *   productKey + dn → 用于 Redis 路由查找
     *
     * @param deviceId   设备数据库 ID
     * @param service    服务名称（物模型中定义的服务标识符）
     * @param args       服务参数 Map
     * @param checkOwner 是否校验设备归属权限
     */
    public void invokeService(Long deviceId, String service,
                              Map<String, Object> args, boolean checkOwner) {
        // 1. 查设备信息，拿到 productKey 和 dn（设备全局标识）
        DeviceInfo device = getAndCheckDevice(deviceId, checkOwner);
        // 2. 构造 ThingModelMessage 并通过消息队列下发
        //    type="service" → ThingComponent.onMessage() 中走 serviceInvoke() 分支
        //    identifier=服务名 → Ge100dComponent.serviceInvoke() 中用作匹配
        send(deviceId, device.getProductKey(), device.getDn(),
                args, ThingModelMessage.TYPE_SERVICE, service);
    }

    /**
     * 【OTA 固件升级】- 向设备下发固件升级包信息
     *
     * OTA 消息结构：type="ota", identifier="ota", data=OtaData 对象
     * OtaData 包含：url（下载地址）、version（版本号）、md5（校验值）、sign（签名）等
     *
     * @param deviceId   设备 ID
     * @param checkOwner 是否校验权限
     * @param data       OTA 升级数据
     */
    @Override
    public void otaUpgrade(Long deviceId, boolean checkOwner, Object data) {
        DeviceInfo device = getAndCheckDevice(deviceId, checkOwner);
        send(deviceId, device.getProductKey(), device.getDn(),
                data, ThingModelMessage.TYPE_OTA, "ota");
    }

    /**
     * 【属性获取】- 请求设备上报指定属性的当前值
     *
     * 设备收到后会读取传感器数据，通过 PropertyReport（属性上报）返回当前值。
     * 这是一个异步请求-响应模式，平台不会阻塞等待结果。
     *
     * @param deviceId   设备 ID
     * @param properties 属性标识符列表（如 ["temperature","humidity"]）
     * @param checkOwner 是否校验权限
     */
    @Override
    public void getProperty(Long deviceId, List<String> properties,
                            boolean checkOwner) {
        DeviceInfo device = getAndCheckDevice(deviceId, checkOwner);

        send(deviceId, device.getProductKey(), device.getDn(), properties,
                ThingModelMessage.TYPE_PROPERTY, ThingModelMessage.ID_PROPERTY_GET);
    }

    /**
     * 【属性设置】- 默认开启权限检查
     */
    @Override
    public void setProperty(Long deviceId, Map<String, Object> properties) {
        setProperty(deviceId, properties, true);
    }

    /**
     * 【属性设置（带权限检查）】- 修改设备属性值
     *
     * 这是最常用的设备控制操作。属性设置是异步的：
     *  1. 平台下发 PropertySet 指令
     *  2. 设备收到后执行（如开关灯、调节温度）
     *  3. 设备通过 PropertyReport 上报实际状态
     *
     * @param deviceId   设备 ID
     * @param properties 属性键值对（如 {"power_switch": true}）
     * @param checkOwner 是否校验权限
     */
    @Override
    public void setProperty(Long deviceId, Map<String, Object> properties,
                            boolean checkOwner) {
        DeviceInfo device = getAndCheckDevice(deviceId, checkOwner);

        send(deviceId, device.getProductKey(), device.getDn(), properties,
                ThingModelMessage.TYPE_PROPERTY, ThingModelMessage.ID_PROPERTY_SET);
    }

    /**
     * 【配置下发（带权限检查）】
     *
     * 从数据库读取设备配置（DeviceConfigService），构造 ThingModelMessage 下发给设备。
     * 如果设备没有配置信息，抛出异常。
     *
     * @param deviceId   设备 ID
     * @param checkOwner 是否校验权限
     */
    @Override
    public void sendConfig(Long deviceId, boolean checkOwner) {
        DeviceInfo device = getAndCheckDevice(deviceId, checkOwner);
        DeviceConfig config = deviceConfigService.findByDeviceId(deviceId);
        if (config == null || StrUtil.isBlank(config.getConfig())) {
            throw new ServiceException(400, "device config is empty, cannot send");
        }
        Map data = JsonUtils.parseObject(config.getConfig(), Map.class);
        send(deviceId, device.getProductKey(), device.getDn(), data,
                ThingModelMessage.TYPE_CONFIG, ThingModelMessage.ID_CONFIG_SET);

    }

    /**
     * 【配置下发】- 默认开启权限检查
     */
    @Override
    public void sendConfig(Long deviceId) {
        sendConfig(deviceId, true);
    }

    /**
     * 【获取并校验设备信息】
     *
     * 查询设备信息，为后续的 send() 操作提供 pk（ProductKey）和 dn（DeviceName）。
     * 当前实现直接调用 deviceInfoService.getDeviceInfo()，checkOwner 参数预留扩展。
     *
     * @param deviceId   设备 ID
     * @param checkOwner 是否校验设备归属权限（预留扩展）
     * @return 设备信息（包含 productKey、deviceName 等关键字段）
     */
    @Override
    public DeviceInfo getAndCheckDevice(Long deviceId, boolean checkOwner) {
        return deviceInfoService.getDeviceInfo(deviceId);
    }


    /**
     * 【解绑子设备】- 通知网关解除与子设备的绑定关系
     *
     * 流程：
     *  1. 获取第一个子设备信息
     *  2. 找到其父网关设备
     *  3. 构造拓扑变更消息（status=1 表示解绑）
     *  4. 通过 send() 下发给网关
     *
     * @param subDeviceIds 待解绑的子设备 ID 列表
     */
    public void unbindDevice(List<Long> subDeviceIds) {
        if (CollectionUtil.isEmpty(subDeviceIds)){
            return;
        }
        Long deviceId = subDeviceIds.get(0);
        DeviceInfo deviceInfo = deviceInfoService.getDeviceInfoFromCache(deviceId);
        if (deviceInfo == null) {
            return;
        }
        Long parentId = deviceInfo.getParentId();
        if (ObjectUtil.isNull(parentId)) {
            return;
        }
        DeviceInfo parent = deviceInfoService.getDeviceInfoFromCache(parentId);
        List<DeviceInfo> subList =deviceInfoService.getDeviceInfoList(subDeviceIds);
        sendUnbindMsg(subList, parent);
    }

    /**
     * 【发送解绑消息】- 构造拓扑变更消息并下发给网关
     *
     * status=1 表示解绑（移除子设备关系）
     *
     * @param subList 子设备列表
     * @param parent  网关设备
     */
    private void sendUnbindMsg(List<DeviceInfo> subList, DeviceInfo parent) {
        if (parent == null) {
            log.error("sendUnbindMsg : parent device not found: {}", parent.getDn());
            return;
        }
        if(CollectionUtil.isEmpty(subList)){
            log.error("sendUnbindMsg : sub device not found: {}", parent.getDn());
            return;
        }

        try {
            List<DeviceTopoChangeDTO.DeviceInfo> changeDeviceList
                    = subList.stream().map(device -> {
                DeviceTopoChangeDTO.DeviceInfo d = new DeviceTopoChangeDTO.DeviceInfo();
                d.setDn(device.getDn());
                d.setPk(device.getProductKey());
                return d;
            }).collect(Collectors.toList());

            // status=1 表示解绑（解除拓扑关系）
            DeviceTopoChangeDTO changeBo = DeviceTopoChangeDTO.builder().status(1).subList(changeDeviceList).build();
            //下发子设备注销给网关
            send(parent.getId(), parent.getProductKey(), parent.getDn(),
                    changeBo,
                    ThingModelMessage.TYPE_TOPO_CHANGE, ThingModelMessage.ID_CHANGE);
        } catch (Throwable e) {
            log.error("send {} message error", ThingModelMessage.ID_CHANGE, e);
        }
        return ;
    }

    /**
     * 【绑定子设备】- 通知网关建立与子设备的绑定关系
     *
     * @param subDeviceIds 子设备 ID 列表
     * @param parentId     网关设备 ID
     */
    @Override
    public void bindDevice(List<Long> subDeviceIds, Long parentId) {
        if (CollectionUtil.isEmpty(subDeviceIds)){
            return;
        }
        if (ObjectUtil.isNull(parentId)) {
            return;
        }
        DeviceInfo parent = deviceInfoService.getDeviceInfoFromCache(parentId);
        List<DeviceInfo> subList =deviceInfoService.getDeviceInfoList(subDeviceIds);

        sendBindMsg(subList, parent);
    }

    /**
     * 【发送绑定消息】- 构造拓扑变更消息并下发给网关
     *
     * status=0 表示绑定（建立拓扑关系）
     *
     * @param subList 子设备列表
     * @param parent  网关设备
     */
    private void sendBindMsg(List<DeviceInfo> subList, DeviceInfo parent) {
        if (parent == null) {
            log.error("sendUnbindMsg : parent device not found: {}", parent.getDn());
            return;
        }
        if(CollectionUtil.isEmpty(subList)){
            log.error("sendUnbindMsg : sub device not found: {}", parent.getDn());
            return;
        }

        try {
            List<DeviceTopoChangeDTO.DeviceInfo> changeDeviceList
                    = subList.stream().map(device -> {
                DeviceTopoChangeDTO.DeviceInfo d = new DeviceTopoChangeDTO.DeviceInfo();
                d.setDn(device.getDn());
                d.setPk(device.getProductKey());
                return d;
            }).collect(Collectors.toList());

            // status=0 表示绑定（建立拓扑关系）
            DeviceTopoChangeDTO changeBo = DeviceTopoChangeDTO.builder().status(0).subList(changeDeviceList).build();
            //下发子设备注册给网关
            send(parent.getId(), parent.getProductKey(), parent.getDn(),
                    changeBo,
                    ThingModelMessage.TYPE_TOPO_CHANGE, ThingModelMessage.ID_CHANGE);
        } catch (Throwable e) {
            log.error("send {} message error", ThingModelMessage.ID_CHANGE, e);
        }
        return ;
    }

    /**
     * 【统一发送入口】- 所有下行消息的最终汇聚点
     *
     * 该方法负责：
     *  1. 构造 ThingModelMessage（统一的物模型消息格式）
     *     - id/mid：UUID 消息唯一标识
     *     - time：当前时间戳
     *     - type：消息类型（property/service/config/ota/topo_change）
     *     - identifier：操作标识符（set/get/服务名等）
     *     - productKey/dn：设备全局标识
     *     - deviceId：设备数据库 ID
     *     - data：消息载荷数据
     *
     *  2. 根据设备类型选择下发通道：
     *     ┌─ 虚拟设备（VirtualDevice）：
     *     │  → VirtualManager.send(message)
     *     │  → JS 脚本引擎的 receive() 方法被调用
     *     │  → 脚本模拟设备行为，返回响应消息
     *     │  → 响应消息直接发布到 device_thing topic
     *     │
     *     └─ 真实设备：
     *        → ComponentManager.sendToDevice(message)
     *        → Redis 路由查找（str:iot:device:router:{pk}:{dn}）
     *        → MQ 发布到 {componentType}_device_thing topic
     *        → 组件消费 → ThingComponent.onMessage() → 协议下发
     *
     * @param deviceId   设备数据库 ID
     * @param pk         产品 Key（ProductKey）
     * @param dn         设备名称（DeviceName）
     * @param data       消息载荷数据
     * @param type       消息类型（TYPE_PROPERTY/TYPE_SERVICE/TYPE_CONFIG 等）
     * @param identifier 操作标识符（"get"/"set"/服务名/"ota" 等）
     */
    /**
     * 【统一发送入口】- 所有下行消息的最终汇聚点
     *
     * <p>将上层传入的参数构造为 ThingModelMessage，根据设备类型选择下发通道。</p>
     *
     * <h3>参数映射关系</h3>
     * <table>
     *   <tr><th>方法参数</th><th>ThingModelMessage 字段</th><th>说明</th></tr>
     *   <tr><td>deviceId</td><td>deviceId</td><td>设备数据库ID</td></tr>
     *   <tr><td>pk</td><td>productKey</td><td>产品Key，用于 Redis 路由查找</td></tr>
     *   <tr><td>dn</td><td>dn</td><td>设备名称，用于 Redis 路由查找</td></tr>
     *   <tr><td>type</td><td>type</td><td>"service"/"property"/"config"/"ota"</td></tr>
     *   <tr><td>identifier</td><td>identifier</td><td>服务名/"set"/"get"/"report"</td></tr>
     *   <tr><td>data</td><td>data</td><td>消息载荷（Map/List/对象）</td></tr>
     * </table>
     *
     * <h3>下发通道选择</h3>
     * <pre>
     * 虚拟设备 → VirtualManager.send(message)
     *   → JS 脚本引擎模拟设备响应，不经过真实硬件
     *
     * 真实设备 → ComponentManager.sendToDevice(message)
     *   → Redis 查路由 str:iot:device:router:{pk}:{dn}
     *   → MQ 发布到 {componentType}_device_thing（如 ge100d_device_thing）
     *   → 组件消费 → 协议下发（MQTT/TCP/HTTP）→ 硬件
     * </pre>
     */
    private void send(Long deviceId, String pk, String dn,
                      Object data, String type, String identifier) {
        // 构造 ThingModelMessage 对象
        ThingModelMessage message = ThingModelMessage.builder()
                .id(IdUtil.fastSimpleUUID())      // 消息唯一标识（UUID）
                .time(System.currentTimeMillis()) // 消息时间戳
                .type(type)                       // 消息类型：service/property/config/ota
                .productKey(pk)                   // 产品Key（设备全局标识的一部分）
                .dn(dn)                           // 设备名称（设备全局标识的一部分）
                .deviceId(deviceId)               // 设备数据库主键ID
                .mid(IdUtil.fastSimpleUUID())     // 消息去重ID（与 id 相同）
                .identifier(identifier)           // 操作标识符：服务名/set/get/ota
                .data(data)                       // 消息载荷数据
                .build();                         // 构建不可变对象
        // 根据设备类型选择下发通道
        if (virtualManager.isPresent() && virtualManager.get().isVirtual(deviceId)) {
            // 虚拟设备：走 JS 脚本引擎模拟，不经过真实硬件
            virtualManager.get().send(message);
        } else {
            // 真实设备：走消息队列路由
            // → ComponentManager.sendToDevice()
            //   → Redis 路由查找
            //     → MQ 发布到组件专属 topic
            //       → 组件消费 → 协议下发 → 硬件
            componentManager.sendToDevice(message);
        }
    }

}
