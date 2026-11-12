
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
package com.enjoyiot.eiot.component.core.model.up;

import com.enjoyiot.eiot.component.core.model.AbstractAction;
import lombok.Data;
import lombok.experimental.SuperBuilder;

/**
 * 【上报行为抽象基类】- 所有上行消息（设备 → 平台）的标记类
 *
 * 在 IoT 物模型架构中，消息分为两大方向：
 *  - 上行（Up/Report）：设备主动上报给平台，如属性上报、事件上报、心跳、注册等
 *  - 下行（Down）：平台下发给设备，如属性设置、服务调用、OTA 升级等
 *
 * ReportAction 作为上行消息的抽象标记类，本身不添加新字段，
 * 其所有子类（PropertyReport、EventReport、DeviceRegister 等）都表示设备上报行为。
 *
 * 【设计意图】
 *  通过继承链区分消息方向：
 *    AbstractAction → ReportAction（上行标记）→ 具体上报类
 *    AbstractAction → 直接子类（下行消息）
 *
 *  这样在 ThingComponent.report() 方法中，可以统一处理所有 ReportAction 子类，
 *  将它们转换为 ThingModelMessage 发送到消息队列。
 */
@Data
@SuperBuilder
public abstract class ReportAction extends AbstractAction {
}
