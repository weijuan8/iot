/*
 *
 *  * | Licensed 未经许可不能去掉「EnjoyIot」相关版权
 *  * +----------------------------------------------------------------------
 *  * | Author: xw2sy@163.com
 *  * +----------------------------------------------------------------------
 *
 *  Copyright [2024] [EnjoyIot]
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

package com.enjoyiot.eiot.component.core.model.down;



import com.enjoyiot.eiot.component.core.model.AbstractAction;
import com.enjoyiot.eiot.component.core.model.ActionType;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * 【OTA 固件升级】- 平台向设备下发固件升级指令（下行消息）
 *
 * OTA（Over-The-Air）是 IoT 平台的核心能力之一，允许远程更新设备固件。
 * 当平台检测到设备固件版本低于最新版本时，自动或手动触发 OTA 升级流程。
 *
 * 【完整 OTA 升级流程】
 *  1. 管理员上传新固件到平台（OSS/文件服务器）
 *  2. 创建 OTA 升级任务，指定目标设备/产品
 *  3. biz 层构造 ThingModelMessage（type="ota"）
 *  4. 消息经过组件路由下发给设备
 *  5. 设备收到 OTA 指令后，从 URL 下载固件包
 *  6. 设备校验 MD5/Sign 后执行升级
 *  7. 设备重启后重新注册，上报新版本号
 *
 * 【OtaData 内部类说明】
 *  包含固件包的所有元信息：下载地址、大小、MD5校验值、签名等
 *  设备端使用这些信息完成安全可靠的固件升级
 *
 * @author sjg
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class DeviceOta extends AbstractAction {

    /**
     * OTA 升级数据（通常为 OtaData 对象）
     * 包含固件包的所有元信息
     */
    private Object data;

    @Override
    public ActionType getType() {
        return ActionType.OTA;
    }

    /**
     * 【OTA 升级数据包】- 固件升级的详细元信息
     *
     * 设备端收到此数据后：
     *  1. 根据 url 下载固件包
     *  2. 使用 md5 校验文件完整性
     *  3. 使用 sign + signMethod 验证签名（防篡改）
     *  4. 根据 isDiff 判断是差分升级还是全量升级
     *  5. 执行升级并重启
     */
    @Builder
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OtaData{
        /**
         * 固件包大小（字节）
         * 设备端用于判断是否有足够存储空间
         */
        private Long size;

        /**
         * 固件包数字签名
         * 用于验证固件包的来源合法性，防止恶意固件注入
         */
        private String sign;

        /**
         * 是否差分升级（增量升级）
         * true = 仅下载差异部分（节省流量）
         * false = 下载完整固件包
         */
        private Boolean isDiff;

        /**
         * 固件包 MD5 校验值
         * 设备下载完成后校验文件完整性
         */
        private String md5;

        /**
         * 固件包名称
         */
        private String name;

        /**
         * 固件版本描述/更新日志
         */
        private String remark;

        /**
         * 目标固件版本号
         * 设备升级后应与此版本一致
         */
        private String version;

        /**
         * 固件包下载地址（HTTP/HTTPS）
         * 设备从此地址下载固件
         */
        private String url;

        /**
         * 签名算法
         * 例如：MD5、SHA256、RSA 等
         */
        private String signMethod;

        /**
         * 升级模块名称
         * 用于区分不同模块的固件（如主控、WiFi、蓝牙模块）
         */
        private String module;

        /**
         * 扩展数据
         * 预留字段，用于传递额外的自定义参数
         */
        private String extData;
    }
}
