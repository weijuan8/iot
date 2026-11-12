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
package com.enjoyiot.eiot.ruleengine.rule;


import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.ruleengine.handler.DeviceMessageHandler;
import com.enjoyiot.eiot.ruleengine.listener.DeviceCondition;
import com.enjoyiot.eiot.ruleengine.listener.Listener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 【规则消息处理器】- 规则引擎的入口 Handler，实现内存索引 + 两级匹配
 *
 * 这是 Handler 链中的第 5 个（最后）处理器，负责将消息与用户定义的规则进行匹配。
 * 它使用 HashMap 作为内存索引，实现 O(1) 的规则查找效率。
 *
 * 【内存索引结构】
 *  deviceRuleMap = Map<String, List<Rule>>
 *    Key: 匹配键，格式为 "{productKey}/{deviceName}"
 *         - "{pk}/#"  → 产品级规则（匹配该产品下所有设备）
 *         - "{pk}/{dn}" → 设备级规则（只匹配指定设备）
 *    Value: 匹配到该 Key 的规则列表
 *
 * 【两级匹配策略】
 *  收到消息后，进行两级匹配（两级结果合并，Set 去重）：
 *
 *  第1级 - 产品级匹配（pk/#）：
 *    - Key = message.productKey + "/#"
 *    - 匹配该产品下所有设备的规则
 *    - 例如：产品"temp_sensor"设置了"温度>30告警"规则，该产品下所有设备都适用
 *
 *  第2级 - 设备级匹配（pk/dn）：
 *    - Key = message.productKey + "/" + message.dn
 *    - 只匹配针对特定设备的规则
 *    - 例如：为"设备A"单独设置了一条特殊的阈值规则
 *
 * 【规则注册与删除】
 *  - putRule()：由 RuleManager.add() 调用，将规则按监听器条件拆分后存入索引
 *  - removeRule()：由 RuleManager.remove() 调用，从索引中删除规则
 *  - 两者都是 synchronized 方法，保证多线程安全
 *
 * 【执行顺序】第 5 位（最后）
 *  在所有系统级处理（日志、属性、状态、配置）完成后，最后执行用户自定义的业务规则。
 *  这确保了规则匹配时，设备的最新属性值和状态已就绪。
 */
@Component
public class RuleMessageHandler implements DeviceMessageHandler {

    /**
     * 【内存规则索引】- 核心数据结构
     * Key: "{productKey}/{deviceName}" 或 "{productKey}/#"
     * Value: 匹配该 Key 的规则列表
     *
     * 使用普通 HashMap + synchronized 保证线程安全（规则变更频率低，锁竞争小）
     */
    // final 确保引用不可变，避免意外替换整个 Map 导致索引丢失
    // HashMap 初始容量默认 16，规则数量通常在百级以下，无需预设大容量
    private final Map<String, List<Rule>> deviceRuleMap = new HashMap<>();

    // 规则执行器，负责四道关卡匹配（Listener → Filter → RateLimit → Action）
    @Autowired
    private RuleExecutor ruleExecutor;

    /**
     * 【注册规则】- 将规则按监听器条件拆分后存入内存索引
     *
     * 拆分逻辑：
     *  一条规则可能有多个 Listener，每个 Listener 关注不同的设备。
     *  例如规则 R1 的 Listener 关注"产品A/#"和"产品B/设备C"，
     *  则 R1 会被存入两个索引条目：
     *    - deviceRuleMap["产品A/#"] → [R1]
     *    - deviceRuleMap["产品B/设备C"] → [R1]
     *
     * 重复处理：使用 addedRuleIds Set 去重，确保同一规则不会被重复匹配。
     *
     * @param rule 要注册的规则
     */
    // synchronized 保证多线程安全：管理后台修改规则和执行引擎匹配消息可能并发
    public synchronized void putRule(Rule rule) {
        // 先删除旧规则：规则更新时，旧的监听器条件可能已变化，清空后重新建立索引
        removeRule(rule.getId());

        // 遍历规则的所有监听器，每个监听器关注一组设备条件
        for (Listener<?> listener : rule.getListeners()) {
            // 每个监听器可能有多个条件（关注多个设备），逐个建立索引条目
            for (Object condition : listener.getConditions()) {
                // 只处理 DeviceCondition（设备条件），忽略其他类型的条件
                if (!(condition instanceof DeviceCondition)) {
                    continue;
                }
                // 提取设备标识，格式为 "{pk}/#" 或 "{pk}/{dn}"
                String device = ((DeviceCondition) condition).getDevice();
                // 如果该设备标识首次出现，创建对应的规则列表
                deviceRuleMap.putIfAbsent(device, new ArrayList<>());
                List<Rule> rules = deviceRuleMap.get(device);
                // 防重：一条规则可能有多个监听器关注同一设备，通过 ID 去重避免重复执行
                boolean exists = rules.stream().anyMatch(r -> r != null && r.getId().equals(rule.getId()));
                if (!exists) {
                    rules.add(rule);
                }
            }
        }
    }

    /**
     * 【删除规则】- 从内存索引中移除指定 ID 的规则
     *
     * 遍历所有索引条目，删除匹配的规则，并清理空的条目。
     *
     * @param ruleId 要删除的规则 ID
     */
    // synchronized 与 putRule 共用同一把锁（this），保证索引的读写一致性
    public synchronized void removeRule(Long ruleId) {
        // 使用 Iterator 遍历，因为需要在遍历过程中安全删除空条目
        Iterator<Map.Entry<String, List<Rule>>> iterator = deviceRuleMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<Rule>> entry = iterator.next();
            // 从当前索引条目的规则列表中删除匹配的规则（规则可能通过多个监听器存在多个条目中）
            entry.getValue().removeIf(s -> s != null && s.getId().equals(ruleId));
            // 清理空列表：如果某个设备标识下不再有关联规则，删除该索引条目，防止内存泄漏
            if (entry.getValue().isEmpty()) {
                iterator.remove(); // 使用 Iterator.remove() 而非直接 Map.remove()，避免 ConcurrentModificationException
            }
        }
    }

    /**
     * 【消息处理入口】- 两级匹配 + 规则执行
     *
     * 1. 产品级匹配：查 pk/# 索引
     * 2. 设备级匹配：查 pk/dn 索引
     * 3. 合并结果（Set 去重，避免同一规则被重复执行）
     * 4. 遍历匹配到的规则，调用 RuleExecutor.execute() 执行
     */
    @Override
    public void handle(ThingModelMessage message) {
        String pk = message.getProductKey();
        String dn = message.getDn();
        List<Rule> rules = new ArrayList<>();
        Set<Long> addedRuleIds = new HashSet<>(); // 去重 Set

        // === 第1级匹配：产品级（pk/#） ===
        // 匹配该产品下所有设备的通用规则
        List<Rule> foundRules = deviceRuleMap.get(pk + "/#");
        if (foundRules != null) {
            foundRules.forEach(rule -> {
                if (rule != null && addedRuleIds.add(rule.getId())) {
                    rules.add(rule);
                }
            });
        }

        // === 第2级匹配：设备级（pk/dn） ===
        // 匹配针对特定设备的专用规则
        foundRules = deviceRuleMap.get(pk + "/" + dn);
        if (foundRules != null) {
            foundRules.forEach(rule -> {
                if (rule != null && addedRuleIds.add(rule.getId())) {
                    rules.add(rule);
                }
            });
        }

        // === 执行匹配到的所有规则 ===
        for (Rule rule : rules) {
            ruleExecutor.execute(message, rule);
        }
    }

}
