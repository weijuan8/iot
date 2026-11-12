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
package com.enjoyiot.eiot.ruleengine.handler.sys;


import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import com.enjoyiot.eiot.common.constant.Constants;
import com.enjoyiot.eiot.common.enums.DeviceState;
import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.message.core.MqProducer;
import com.enjoyiot.eiot.ruleengine.handler.DeviceMessageHandler;
import com.enjoyiot.framework.common.util.collection.CollectionUtils;
import com.enjoyiot.module.eiot.api.device.DeviceApi;
import com.enjoyiot.module.eiot.api.device.dto.DeviceInfo;
import com.enjoyiot.module.eiot.api.product.ProductApi;
import com.enjoyiot.module.eiot.api.product.dto.Product;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 【设备状态检查处理器】- 管理设备在线状态与最后活跃时间
 *
 * <p>这是 Handler 链中的第 3 个处理器（@Order(3)），负责：</p>
 * <ol>
 *   <li>更新设备最后活跃时间（LastTime）到 Redis</li>
 *   <li>检测设备状态变化（未激活→在线 / 在线→离线 / 离线→在线）并更新 MySQL 数据库</li>
 *   <li>网关设备状态变化时，同步更新其透明传输子设备的状态</li>
 *   <li>当设备从离线/未激活变为在线时，发布 STATE 类型消息通知系统其他模块</li>
 * </ol>
 *
 * <h2>设备状态码说明</h2>
 * <pre>
 * DeviceInfo.STATE_NOT_ACTIVE = 2  →  未激活（设备创建后初始状态，从未上报过数据）
 * DeviceInfo.ONLINE          = 1  →  在线（正在上报数据）
 * DeviceInfo.OFFLINE         = 0  →  离线（曾经在线，但超时未通信）
 * DeviceInfo.STATE_LOCK      = 3  →  禁用（管理员手动禁用）
 * </pre>
 *
 * <h2>状态流转逻辑</h2>
 * <pre>
 * 创建设备（state=2 未激活）
 *     │
 *     │ 首次上报任何上行消息（property/event/service/...）
 *     ▼
 *  state=1 在线 ─────────────────────────────┐
 *     │                                       │
 *     │ 持续上报，每次更新 Redis lastTime       │
 *     │                                       │
 *     │ 停止上报                               │
 *     ▼                                       │
 *  DeviceStateCheckTask 定时任务检测超时        │
 *  （lastTime 距今 > keepAliveTime × 1.1）     │
 *     │                                       │
 *     │ 发送 offline 消息                       │
 *     ▼                                       │
 *  state=0 离线                                │
 *     │                                       │
 *     │ 再次上报任意上行消息                     │
 *     └───────────────────────────────────────┘
 * </pre>
 *
 * <h2>与定时任务的协作</h2>
 * 本 Handler 负责"上线检测"（设备上报 → 立即标记在线），
 * {@link com.enjoyiot.module.eiot.service.iot.DeviceStateCheckTask} 负责"离线检测"（定时轮询 → 超时标记离线）。
 * 两者通过 Redis 中的 lastTime 字段协作：
 * <pre>
 * 本 Handler：收到消息 → 更新 Redis lastTime → 判定上线
 * 定时任务：  读取 Redis lastTime → 判定超时 → 发送离线消息 → 本 Handler 收到离线消息 → 更新 MySQL state=0
 * </pre>
 *
 * <h2>为什么收到任意上行消息就认为在线？</h2>
 * IoT 场景下设备不一定都会发送 state 类型的上下线消息。很多低功耗设备只会上报属性数据。
 * 因此"收到数据"本身就意味着设备在线，这是一种更通用的在线判定方式。
 *
 * <h2>子设备状态同步</h2>
 * 网关设备（NODE_TYPE_GATEWAY）上线/离线时，其下所有"透明传输"模式的子设备也跟随变更状态。
 * 透明传输（transparent=true）：子设备数据由网关直接转发，不建立独立 MQTT 连接。
 *
 * @author sjg
 * @see com.enjoyiot.module.eiot.service.iot.DeviceStateCheckTask 离线检测定时任务
 * @see com.enjoyiot.eiot.common.enums.DeviceState 逻辑状态枚举（ONLINE/OFFLINE）
 */
@Slf4j
@Component
public class DeviceStateCheckHandler implements DeviceMessageHandler {

    /**
     * 设备 API —— 用于查询设备信息、更新设备状态、更新最后活跃时间
     * 底层通过 DeviceApiImpl → DeviceInfoService → MySQL + Redis
     */
    @Autowired
    private DeviceApi deviceApi;

    /**
     * 消息生产者 —— 当检测到设备从离线变为在线时，发布 STATE 类型消息到 device_thing topic
     * 这个消息会被规则引擎重新消费，触发规则匹配（如"设备上线"告警规则）
     */
    @Resource
    private MqProducer<ThingModelMessage> producer;

    /**
     * 【核心入口】处理每一条经过规则引擎的设备消息
     *
     * <p>调用链：</p>
     * <pre>
     * 设备上报 → EmqxVerticle/MqttComponent → ThingComponent.report()
     *   → sendMessage() → device_thing topic
     *   → RuleDeviceConsumer.handler()（按 @Order 顺序调用 Handler）
     *     → Handler #1: DeviceMessageLogHandler    — 原始日志入库
     *     → Handler #2: DevicePropertyHandler      — 属性白名单过滤 + 时序存储
     *     → Handler #3: DeviceStateCheckHandler    — 本方法 ★
     *     → Handler #4: DeviceConfigHandler        — 配置回复
     *     → Handler #5: RuleMessageHandler         — 用户规则匹配
     * </pre>
     *
     * <h2>本方法的核心判断逻辑</h2>
     * <table>
     *   <tr><th>当前 MySQL state</th><th>消息类型</th><th>判定结果</th></tr>
     *   <tr><td>未激活(2)</td><td>任意上行消息</td><td>→ 变为在线(1)，发送上线通知</td></tr>
     *   <tr><td>在线(1)</td><td>任意上行消息</td><td>→ 维持在线，只更新 lastTime</td></tr>
     *   <tr><td>在线(1)</td><td>state/offline 消息</td><td>→ 变为离线(0)</td></tr>
     *   <tr><td>离线(0)</td><td>任意上行消息</td><td>→ 变为在线(1)，发送上线通知</td></tr>
     *   <tr><td>离线(0)</td><td>state/offline 消息</td><td>→ 维持离线，不处理</td></tr>
     * </table>
     *
     * @param msg 物模型消息，包含 type、identifier、data、deviceId、time 等字段
     */
    @Override
    public void handle(ThingModelMessage msg) {
        // ========================================================================
        // 步骤 1: 过滤下行消息
        // ========================================================================
        // 下行消息（toClient=true）是平台发给设备的（如属性设置、服务调用），
        // 不是设备上报的。只有设备主动上报的"上行消息"才说明设备在线。
        // 例如：管理员通过后台设置设备属性 → toClient=true → 跳过
        if (Boolean.TRUE.equals(msg.getToClient())) {
            return;
        }

        // 获取消息类型，用于后续判断
        // type 可能值：property/event/service/state/config/ping/ota/lifetime 等
        String type = msg.getType();

        // ========================================================================
        // 步骤 2: 从 Redis 缓存中获取设备信息
        // ========================================================================
        // deviceId 是 ThingComponent.report() 中通过 DeviceApi.getDeviceByPkDnByCache()
        // 查询 eiot_device_info 表得到的数据库主键 ID。
        // Redis 缓存 Key = eiot:device:id:{deviceId}
        // 缓存由 @Cacheable 注解自动管理（DeviceInfoServiceImpl.getDeviceInfoFromCache）
        DeviceInfo device = deviceApi.getDeviceInfoFromCache(msg.getDeviceId());
        if (device == null) {
            // 设备不存在或缓存失效 → 无法判断状态，直接返回
            // 可能原因：
            //   1. 设备未在系统中创建（eiot_device_info 表无记录）
            //   2. 设备的 productKey/dn 与上报的不匹配
            //   3. Redis 缓存被误清除
            return;
        }

        // ========================================================================
        // 步骤 3: 推断消息对应的"逻辑状态"（DeviceState 枚举）
        // ========================================================================
        // 规则引擎只关心两种状态：ONLINE（在线）和 OFFLINE（离线）
        // 对于设备上报的任何上行消息，默认认为设备在线
        DeviceState state = DeviceState.ONLINE;

        // 获取消息的 identifier（具体操作标识）
        // 属性上报时 identifier="report"，状态变更时 identifier="online"/"offline"
        String identifier = msg.getIdentifier();

        // 特殊处理：如果消息类型是 "state"（状态消息），根据 identifier 判断
        // - identifier="online"  → 设备主动上报上线 → DeviceState.ONLINE
        // - identifier="offline" → 设备主动上报离线 → DeviceState.OFFLINE
        // 这种消息通常由设备端主动发送，或由定时任务 DeviceStateCheckTask 生成
        if (ThingModelMessage.TYPE_STATE.equals(type)) {
            // StringUtils.equals 是 null 安全的字符串比较
            state = StringUtils.equals(identifier, "online") ? DeviceState.ONLINE : DeviceState.OFFLINE;
        }
        // 注意：对于非 state 类型的消息（property/event/service/...），
        // state 保持为上面默认的 DeviceState.ONLINE
        // 这意味着"只要设备在发数据，就认为它在线"

        // ========================================================================
        // 步骤 4: 更新 Redis 中的最后活跃时间（lastTime）
        // ========================================================================
        // 只要设备不是离线状态，就更新 lastTime。
        // lastTime 存储在 Redis 中，Key = eiot:device:last:{deviceId}
        // 这个时间戳会被定时任务 DeviceStateCheckTask 读取，用于判断设备是否超时离线
        if (state != DeviceState.OFFLINE) {
            // msg.getTime() 是消息中携带的时间戳（设备端产生事件的时间或平台收到消息的时间）
            updateDeviceLastTime(device, msg);
        }

        // ========================================================================
        // 步骤 5: 核心判断 —— 设备状态是否发生了变化？
        // ========================================================================
        // 比较 当前 MySQL 中的状态（通过 isOnline() 判断 state==1）
        //     与 本次消息推断出的状态（ONLINE/OFFLINE）
        //
        // device.isOnline() 的实现：
        //   return state != null && state == 1;  // 只有 state=1 才算在线
        //
        // 状态未变化的两种情况（直接 return，不做任何更新）：
        //
        // 情况 A：当前在线(1) 且 新状态也是 ONLINE
        //   → 设备本来就在线，继续在线，无需更新 DB
        //
        // 情况 B：当前不在线(0或2或3) 且 新状态是 OFFLINE
        //   → 设备本来就离线/未激活/禁用，继续离线，无需更新 DB
        //
        // 状态发生变化的两种情况（继续往下执行）：
        //
        // 情况 C：当前不在线(0或2或3) 且 新状态是 ONLINE
        //   → 设备从离线/未激活变为在线，需要更新 DB + 发送上线通知
        //
        // 情况 D：当前在线(1) 且 新状态是 OFFLINE
        //   → 设备从在线变为离线，需要更新 DB
        if ((device.isOnline() && state == DeviceState.ONLINE) ||
                (!device.isOnline() && state == DeviceState.OFFLINE)) {
            // 状态未变化，直接返回
            return;
        }

        // 执行到这里说明状态发生了变化
        log.debug("rule engine state change [{} -> {}] msgId: {}",
                device.isOnline(), state.isOnline(), msg.getMid());

        // ========================================================================
        // 步骤 6: 确定状态变更的时间戳
        // ========================================================================
        Long time = msg.getTime();
        if (time == null) {
            // 如果消息没有时间戳（异常情况），使用当前系统时间
            time = System.currentTimeMillis();
        }

        // 对于非 STATE 类型的消息，时间戳减 1 毫秒
        // 为什么要减 1 毫秒？
        //   时序数据库（TDengine）中，同一设备同一时间戳的数据会覆盖。
        //   这里会用同一个 time 写一条 state 消息到 thing_model_message 表。
        //   如果原始属性消息在 thing_model_message 中已有一条记录（Handler #1 写入的），
        //   新写入的 state 消息若时间完全相同，会覆盖掉那条属性记录。
        //   减 1 毫秒确保两条记录不冲突。
        if (!ThingModelMessage.TYPE_STATE.equals(type)) {
            time = time - 1;
        }

        // ========================================================================
        // 步骤 7: 更新 MySQL 数据库中的设备状态
        // ========================================================================
        // deviceApi.updateDeviceState() → DeviceInfoServiceImpl.updateDeviceState()
        //
        // 实际执行的 SQL（MyBatis-Plus LambdaUpdateWrapper）：
        //   UPDATE eiot_device_info
        //   SET state = 1 (或 0), online_time = ? (或 offline_time = ?)
        //   WHERE id = ?
        //
        // 同时通过 @CacheEvict(cacheNames="eiot:device:id", key="#deviceId")
        // 清除 Redis 中的设备缓存，确保下次 getDeviceInfoFromCache 能读到最新状态
        Boolean b = deviceApi.updateDeviceState(device.getId(), state.isOnline());

        // ========================================================================
        // 步骤 8: 发送状态变更通知（仅限"上线"场景）
        // ========================================================================
        // 条件：DB 更新成功 AND 不是 state 类型消息触发的变更
        //
        // 为什么要排除 state 类型消息？
        //   - 如果原始消息本身就是 type="state"（如 DeviceStateCheckTask 发送的离线通知），
        //     说明状态变更已经作为一条 state 消息进入了系统，不需要再重复发送。
        //   - 如果是 property/event/service 等非 state 消息触发的状态变更，
        //     说明这是"隐式上线"（设备没有主动发上线消息，但通过发数据表明自己在线），
        //     此时需要补发一条上线通知，让规则引擎的其他模块感知到这个变化。
        if (b && !ThingModelMessage.TYPE_STATE.equals(type)) {
            // 构建并发送一条 state/online 消息
            // 这条消息会被 RuleDeviceConsumer 再次消费，
            // DeviceStateCheckHandler 再次收到时因状态已一致会跳过（步骤5的情况A），
            // 但 RuleMessageHandler 可以匹配"设备上线"的规则触发告警或联动
            sendDeviceStateChangeMessage(device, state.isOnline(), time);
        }

        // ========================================================================
        // 步骤 9: 同步更新子设备状态（网关设备场景）
        // ========================================================================
        // 如果当前设备是网关（NODE_TYPE_GATEWAY），其下的透明传输子设备也需要跟随变更状态
        updateSubDeviceState(device, state, b, type, time);
    }

    /**
     * 【子设备状态同步】- 网关状态变化时，同步更新其透明传输子设备
     *
     * <p>当网关设备上线/离线时，其下所有"透明传输"模式的子设备也跟随变更状态。</p>
     *
     * <h2>什么是透明传输？</h2>
     * 透明传输（transparent=true）的子设备不建立独立的网络连接（如不单独连 MQTT Broker），
     * 而是由网关代为转发数据。因此子设备的状态完全取决于网关状态：
     *   - 网关在线 → 子设备被认为在线
     *   - 网关离线 → 子设备被认为离线
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li>判断 device.getNodeType() 是否为 NODE_TYPE_GATEWAY(0)</li>
     *   <li>如果是网关 → 查询该网关下所有子设备（通过 pk+dn 查找）</li>
     *   <li>遍历子设备，只处理 transparent=true 的子设备</li>
     *   <li>对每个子设备执行与主设备相同的状态更新和通知逻辑</li>
     * </ol>
     *
     * @param device 网关设备信息（只有 nodeType==0 时才处理）
     * @param state  新的逻辑状态（ONLINE 或 OFFLINE）
     * @param b      主设备的状态更新是否成功（DB 写入结果）
     * @param type   原始消息的类型（用于判断是否需要发送状态变更通知）
     * @param time   状态变更的时间戳
     */
    private void updateSubDeviceState(DeviceInfo device, DeviceState state, Boolean b, String type, Long time) {
        // 只有网关设备才需要处理子设备
        // NODE_TYPE_GATEWAY = 0，表示该设备是网关类型
        if (DeviceInfo.NODE_TYPE_GATEWAY == device.getNodeType()) {
            // 通过网关的 productKey + dn 查询其下所有子设备
            // 子设备关系存储在 eiot_device_info 的 parent_id 字段中
            List<DeviceInfo> subList = deviceApi.getSubDevicesByProductKeAndDeviceName(
                    device.getProductKey(), device.getDn());

            // 如果网关下有子设备
            if (CollectionUtil.isNotEmpty(subList)) {
                for (DeviceInfo subDevice : subList) {
                    // 跳过 null 对象（防御性编程）
                    if (subDevice == null) {
                        continue;
                    }
                    // 只处理透明传输模式的子设备
                    // transparent=true：子设备由网关转发数据，网关的状态就是子设备的状态
                    // transparent=false：子设备有自己的独立连接，不受网关状态影响
                    if (subDevice.getTransparent()) {
                        // 更新子设备的 MySQL 状态（state = 1 或 0）
                        deviceApi.updateDeviceState(subDevice.getId(), state.isOnline());

                        // 如果主设备的状态更新成功 AND 不是 state 消息触发的
                        // → 也需要为子设备补发一条上线通知
                        if (b && !ThingModelMessage.TYPE_STATE.equals(type)) {
                            sendDeviceStateChangeMessage(subDevice, state.isOnline(), time);
                        }
                    }
                }
            }
        }
    }

    /**
     * 【更新设备最后活跃时间】- 写入 Redis 缓存
     *
     * <p>每次收到设备上行消息都更新 lastTime，这个时间戳被用于心跳超时检测。</p>
     *
     * <h2>数据流</h2>
     * <pre>
     * 本方法 → deviceApi.updateDeviceLastTimeCache(deviceId, msgTime)
     *   → DeviceInfoServiceImpl.updateDeviceLastTimeCache()
     *     → EiotRedisDAO.setLastTime(deviceId, lastTime)
     *       → stringRedisTemplate.opsForValue()
     *           .set("eiot:device:last:{deviceId}", String.valueOf(lastTime))
     * </pre>
     *
     * <h2>读取方</h2>
     * 定时任务 DeviceStateCheckTask.syncState() 每 10 秒读取：
     * <pre>
     * long lastTime = deviceInfoService.getLastTimeCache(deviceId);
     * // → EiotRedisDAO.getLastTime() → GET eiot:device:last:{deviceId}
     * if (System.currentTimeMillis() - lastTime > keepAliveTime * 1000 * 1.1) {
     *     设备超时 → 发送 offline 消息
     * }
     * </pre>
     *
     * @param device 设备信息（用于获取 deviceId）
     * @param msg    物模型消息（用于获取时间戳）
     */
    private void updateDeviceLastTime(DeviceInfo device, ThingModelMessage msg) {
        // 获取消息中的时间戳
        // msg.getTime() 在 ThingComponent.report() 中被设置为：
        //   action.getTime()（设备端提供） 或 System.currentTimeMillis()（平台生成）
        Long msgTimeMilli = msg.getTime();

        // 写入 Redis，key = eiot:device:last:{deviceId}，value = 时间戳字符串
        // 这个操作没有过期时间，因为定时任务会持续对比时间，不需要自动过期
        deviceApi.updateDeviceLastTimeCache(device.getId(), msgTimeMilli);
    }

    /**
     * 【发送设备状态变更消息】- 发布到 device_thing topic
     *
     * <p>当设备从离线/未激活变为在线时（非 STATE 类型消息触发），
     * 发布一条 type="state" 的 ThingModelMessage 到消息队列。</p>
     *
     * <h2>为什么要发送这条消息？</h2>
     * 当设备通过 property/event/service 等非 state 消息"隐式上线"时，
     * 系统的其他模块（如规则引擎的 RuleMessageHandler）无法感知到这个状态变化。
     * 补发一条 state 消息可以：
     * <ol>
     *   <li>让 RuleMessageHandler 匹配"设备上线"类型的用户规则（触发告警/联动）</li>
     *   <li>在 thing_model_message 时序表中留下明确的上线记录</li>
     *   <li>让其他可能监听 device_thing topic 的消费者感知状态变化</li>
     * </ol>
     *
     * <h2>消息结构</h2>
     * <pre>
     * ThingModelMessage {
     *   id: UUID（唯一标识）
     *   type: "state"
     *   identifier: "online" 或 "offline"
     *   deviceId: 设备数据库 ID
     *   productKey: 产品 key
     *   dn: 设备名称
     *   time: 状态变更时间戳
     *   occurred: 同上
     * }
     * </pre>
     *
     * <h2>消息的后续处理</h2>
     * 这条消息被发布到 device_thing topic 后：
     * <ol>
     *   <li>RuleDeviceConsumer 重新消费</li>
     *   <li>Handler #1（DeviceMessageLogHandler）→ 写入 thing_model_message 时序表</li>
     *   <li>Handler #3（本 Handler）→ 再次收到，但因状态已一致会跳过（步骤 5 的情况 A）</li>
     *   <li>Handler #5（RuleMessageHandler）→ 匹配用户规则，触发设备上线告警</li>
     * </ol>
     *
     * @param device 设备信息
     * @param online true=上线, false=离线
     * @param time   状态变更的时间戳
     */
    private void sendDeviceStateChangeMessage(DeviceInfo device, Boolean online, Long time) {
        // 根据在线/离线确定 identifier
        // online=true  → identifier="online"
        // online=false → identifier="offline"
        String stateId = online ? ThingModelMessage.ID_ONLINE : ThingModelMessage.ID_OFFLINE;

        // 构建 ThingModelMessage 对象
        ThingModelMessage msg = ThingModelMessage.builder()
                // id：使用 fastSimpleUUID() 生成无横线的 UUID，作为消息唯一标识
                .id(IdUtil.fastSimpleUUID())
                // time：状态变更的时间戳
                .time(time)
                // dn：设备名称（DeviceName）
                .dn(device.getDn())
                // productKey：产品 key
                .productKey(device.getProductKey())
                // occurred：事件发生时间，与 time 相同
                .occurred(time)
                // deviceId：设备在数据库中的主键 ID
                .deviceId(device.getId())
                // identifier："online" 或 "offline"
                .identifier(stateId)
                // type：固定为 "state"
                .type(ThingModelMessage.TYPE_STATE)
                .build();

        // 发布消息到 "device_thing" topic
        // 底层根据消息中间件实现（Spring Event / Kafka / RocketMQ）进行路由
        // 发布后，RuleDeviceConsumer 会消费这条消息，重新走一遍 Handler 链
        producer.publish(Constants.THING_MODEL_MESSAGE_TOPIC, msg);
    }
}
