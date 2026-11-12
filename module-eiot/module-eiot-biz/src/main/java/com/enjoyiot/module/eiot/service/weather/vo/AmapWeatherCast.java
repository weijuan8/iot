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
package com.enjoyiot.module.eiot.service.weather.vo;

import lombok.Data;

/**
 * 高德地图单日天气预报
 */
@Data
public class AmapWeatherCast {

    /** 日期（如：2024-06-18） */
    private String date;

    /** 星期几（如：2） */
    private String week;

    /** 白天天气现象 */
    private String dayweather;

    /** 晚上天气现象 */
    private String nightweather;

    /** 白天温度，单位：摄氏度 */
    private String daytemp;

    /** 晚上温度，单位：摄氏度 */
    private String nighttemp;

    /** 白天风向 */
    private String daywind;

    /** 晚上风向 */
    private String nightwind;

    /** 白天风力等级 */
    private String daypower;

    /** 晚上风力等级 */
    private String nightpower;
}
