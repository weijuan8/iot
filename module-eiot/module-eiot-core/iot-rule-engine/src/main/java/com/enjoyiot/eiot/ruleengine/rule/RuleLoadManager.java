
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
package com.enjoyiot.eiot.ruleengine.rule;


import cn.hutool.core.collection.CollectionUtil;
import com.enjoyiot.eiot.common.utils.CodecUtil;
import com.alibaba.fastjson.JSON;
import com.enjoyiot.framework.common.pojo.PageResult;
import com.enjoyiot.module.eiot.api.rule.RuleApi;
import com.enjoyiot.module.eiot.api.rule.dto.RuleInfo;
import com.enjoyiot.module.eiot.api.rule.dto.RuleInfoPageReqVO;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 【规则加载管理器】- 定时从数据库加载规则，MD5 变更检测 + 热更新
 *
 * 该类负责规则的初始加载和热更新，通过定时任务每 30 秒扫描数据库中的规则，
 * 对比 MD5 值检测变更，仅对发生变化的规则执行刷新操作。
 *
 * 【工作流程】
 *  1. 构造函数中启动定时任务（每 30 秒执行一次 initRules()）
 *  2. initRules() 分页查询数据库中的所有规则
 *  3. 对每条规则计算 MD5 值，与上次加载时的 MD5 对比
 *  4. MD5 不同 → 规则已变更 → 调用 refreshRule() 刷新
 *  5. MD5 相同 → 规则未变更 → 跳过
 *
 * 【MD5 变更检测的优势】
 *  - 避免每次全量刷新规则（频繁刷新会导致内存索引重建，影响匹配性能）
 *  - 只有真正变更的规则才会触发 refreshRule()
 *  - MD5 计算基于规则的完整 JSON 序列化（包括监听器、过滤器、动作、触发选项等所有字段）
 *
 * 【刷新策略】
 *  refreshRule() = remove() + add()
 *  先删除旧规则（清理索引、缓存、Redis），再添加新规则（重新解析、注册）。
 *  这种方式确保了规则变更的原子性，避免新旧规则共存导致的问题。
 */
@Slf4j
@Component
public class RuleLoadManager {

    @Autowired
    private RuleManager ruleManager;

    /** 规则 API（分页查询数据库中的规则） */
    @Resource
    private RuleApi ruleApi;

    /**
     * 【MD5 缓存】- 存储每条规则上次加载时的 MD5 值
     * Key: 规则 ID
     * Value: 规则的 JSON 序列化后的 MD5 值
     */
    final private Map<Long, String> ruleMd5Map = new HashMap<>();

    /**
     * 【构造函数】- 启动定时加载任务
     *
     * 初始延迟 1 秒，之后每 30 秒执行一次 initRules()。
     */
    public RuleLoadManager() {
        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        executorService.scheduleAtFixedRate(this::initRules, 1, 30, TimeUnit.SECONDS);
    }

    /**
     * 【加载/刷新规则】- 分页查询 + MD5 变更检测
     *
     * 分页遍历数据库中的所有规则，对每条规则进行 MD5 对比，
     * 只有变更的规则才执行刷新操作。
     */
    @SneakyThrows
    public void initRules() {
        int idx = 1;
        int pageSize = 100;
        while (true) {
            RuleInfoPageReqVO pageRequest = new RuleInfoPageReqVO();
            pageRequest.setPageNo(idx);
            pageRequest.setPageSize(pageSize);
            PageResult<RuleInfo> all = ruleApi.selectPage(pageRequest);
            List<RuleInfo> rules = all.getList();
            if (CollectionUtil.isEmpty(rules)) {
                return;
            }

            for (RuleInfo rule : rules) {
                Long ruleId = rule.getId();
                String oldMd5 = ruleMd5Map.get(ruleId);
                // 计算当前规则的 MD5 值（基于 JSON 序列化）
                String md5 = CodecUtil.md5Str(JSON.toJSONString(rule));
                // MD5 相同 → 规则未变更 → 跳过
                if (oldMd5 != null && oldMd5.equals(md5)) {
                    continue;
                }

                // MD5 不同 → 规则已变更 → 刷新
                log.info("rule {} has changed", ruleId);
                ruleMd5Map.put(ruleId, md5);
                refreshRule(rule);
            }
            // 当前页数据不足一页 → 已是最后一页 → 退出
            if (all.getTotal() < pageSize) {
                return;
            }
            idx++;
        }
    }

    /**
     * 【刷新规则】- 先删除旧规则，再添加新规则
     *
     * remove → add 的方式确保规则变更的原子性。
     */
    private void refreshRule(RuleInfo ruleInfo) {
        ruleManager.remove(ruleInfo.getId());
        ruleManager.add(ruleInfo);
    }

}
