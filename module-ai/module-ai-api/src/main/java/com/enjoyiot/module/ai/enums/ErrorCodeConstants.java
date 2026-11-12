
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
package com.enjoyiot.module.ai.enums;

import com.enjoyiot.framework.common.exception.ErrorCode;

public interface ErrorCodeConstants {
    // ... existing code ...

    ErrorCode ALGORITHM_NOT_EXISTS = new ErrorCode(1002002000, "算法不存在");

    // ========== AI 塘口分析 10020021xx ==========
    ErrorCode POND_ANALYSIS_NOT_EXISTS = new ErrorCode(1002002100, "塘口分析结果不存在");
    ErrorCode POND_ANALYSIS_FAIL = new ErrorCode(1002002101, "塘口 AI 分析失败：{}");
    ErrorCode POND_ANALYSIS_DATA_EMPTY = new ErrorCode(1002002102, "塘口数据为空，无法分析");
    ErrorCode DEEPSEEK_CALL_FAIL = new ErrorCode(1002002103, "DeepSeek 调用失败：{}");
    ErrorCode AI_OUTPUT_PARSE_FAIL = new ErrorCode(1002002104, "AI 输出解析失败");
    ErrorCode AI_OUTPUT_VALIDATE_FAIL = new ErrorCode(1002002105, "AI 输出校验失败：{}");

    // ... existing code ...
}