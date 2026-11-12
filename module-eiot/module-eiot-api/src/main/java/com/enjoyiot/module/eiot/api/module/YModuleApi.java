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
package com.enjoyiot.module.eiot.api.module;

/**
 * 模块 API（供组件层调用，跳过租户和超管权限校验）
 *
 * @author EnjoyIot
 */
public interface YModuleApi {

    /**
     * 按模块 ID 查找模块
     *
     * 注意：该方法内部已用平台租户上下文 + 跨租户条件，
     *       调用方无需关心租户隔离，传入 moduleId 即可。
     *
     * @param moduleId 模块 ID（y_module.id 主键，由设备上行 topic 携带）
     * @return 模块信息；不存在返回 null
     */
    YModuleInfo getById(Long moduleId);

    /**
     * 系统内部绑定（GEIO 自动注册等场景专用，跳过租户和超管权限校验）
     *
     * 调用方必须保证：moduleId/deviceId 都已校验存在，且 device_id 不在 y_module_device 中
     *
     * @param moduleId 模块 ID
     * @param deviceId 设备 ID
     */
    void bindDeviceInternal(Long moduleId, Long deviceId);
}