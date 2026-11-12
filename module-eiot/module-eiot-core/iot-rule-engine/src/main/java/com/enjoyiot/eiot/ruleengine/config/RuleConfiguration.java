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
package com.enjoyiot.eiot.ruleengine.config;


import com.enjoyiot.eiot.common.thing.ThingModelMessage;
import com.enjoyiot.eiot.message.core.MqConsumer;
import com.enjoyiot.eiot.ruleengine.handler.RuleDeviceConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【规则引擎配置类】- 注册规则引擎消费者 Bean
 *
 * 该配置类负责将 RuleDeviceConsumer 注册为 Spring Bean，
 * 使其能够从消息队列消费 device_thing topic 上的设备消息。
 *
 * RuleDeviceConsumer 在构造函数中订阅消息队列，因此只需注册 Bean 即可自动开始消费。
 *
 * @see RuleDeviceConsumer 规则引擎消费者总调度器
 */
@Configuration
public class RuleConfiguration {

    /**
     * 创建并注册 RuleDeviceConsumer Bean
     *
     * @param consumer MQ 消费者实例（由消息总线模块自动注入，默认为 Spring Event 实现）
     * @return RuleDeviceConsumer 实例（已自动订阅 device_thing topic）
     */
    @Bean
    public RuleDeviceConsumer getConsumer(MqConsumer<ThingModelMessage> consumer) {
        return new RuleDeviceConsumer(consumer);
    }

}
