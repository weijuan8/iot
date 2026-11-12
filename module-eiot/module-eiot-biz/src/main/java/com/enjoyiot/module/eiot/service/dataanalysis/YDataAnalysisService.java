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
package com.enjoyiot.module.eiot.service.dataanalysis;

import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisDeviceVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisExportReqVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisExportRespVO;
import com.enjoyiot.module.eiot.controller.admin.dataanalysis.vo.YDataAnalysisSeriesRespVO;

import java.util.List;

/**
 * 数据分析 Service 接口（TDengine 时序聚合，SQL 层面完成统计）
 *
 * @author EnjoyIot
 */
public interface YDataAnalysisService {

    /**
     * 获取模块下的设备及其可分析属性（按设备分组：设备 -> 该设备的数值型属性，且设备实际有上报数据）
     *
     * @param moduleId 模块编号
     * @return 设备列表（含属性配置）
     */
    List<YDataAnalysisDeviceVO> yGetProperties(Long moduleId);

    /**
     * 获取单台设备单个属性在时间范围内的时序数据（INTERVAL 降采样）
     *
     * @param moduleId         模块编号（用于定位设备，确认产品物类型以确定 TDengine 超级表）
     * @param key              属性标识符（物模型 key）
     * @param deviceId         设备编号（必传；只查该设备该属性的趋势，不做多设备聚合）
     * @param startTs          开始时间戳（毫秒），为空默认 24 小时前
     * @param endTs            结束时间戳（毫秒），为空默认当前时间
     * @param intervalMinutes  降采样窗口（分钟），与 maxPoints 二选一
     * @param maxPoints        目标返回点数，后端据此计算 INTERVAL 窗口，控制返回量（intervalMinutes 为空时生效）
     * @return 时序结果
     */
    YDataAnalysisSeriesRespVO yGetSeries(Long moduleId, String key, Long deviceId,
                                        Long startTs, Long endTs, Integer intervalMinutes,
                                        Integer maxPoints);

    /**
     * 导出数据：多设备多属性在统一时间网格下的批量查询（给前端"导出 Excel/CSV"按钮专用）
     *
     * <p>与 {@link #yGetSeries} 完全独立：不复用单设备查询路径，直接按降采样窗口生成
     * 统一时间网格 + 一次性按 (deviceId, key) 拉数据，避免 N 次 HTTP 请求；返回结构
     * (headers + rows) 对前端导出友好，可直接套模板生成 Excel/CSV。</p>
     *
     * <p>采样点 vs 实际数据点：当用户选的采样间隔小于设备实际数据密度时，窗口内可能
     * 无数据，rows 对应单元格为空字符串。这是 TDengine INTERVAL 按窗口聚合的预期行为，
     * 不做插值填充（避免引入虚假数据）。</p>
     *
     * @param req 导出请求（模块 + 时间范围 + 降采样间隔 + 多个 (deviceId, key) 项）
     * @return 导出结果（headers + rows）
     */
    YDataAnalysisExportRespVO yExport(YDataAnalysisExportReqVO req);

}
