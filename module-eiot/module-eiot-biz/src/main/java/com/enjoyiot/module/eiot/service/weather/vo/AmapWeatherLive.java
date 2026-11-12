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
 * 高德地图实况天气
 */
@Data
public class AmapWeatherLive {

    /** 省份名 */
    private String province;

    /** 城市名 */
    private String city;

    /** 区域编码 */
    private String adcode;

    /** 天气现象（如：晴、多云） */
    private String weather;

    /** 实时气温，单位：摄氏度 */
    private String temperature;

    /** 风向（如：东南风） */
    private String winddirection;

    /** 风力等级（如：2） */
    private String windpower;

    /** 空气湿度（如：55） */
    private String humidity;

    /** 数据发布的时间 */
    private String reporttime;
}
