package com.enjoyiot.module.eiot.controller.admin.weather.vo;

import lombok.Data;

/**
 * 天气 响应 VO（实况 + 今日预报，来自高德地图天气查询）
 *
 * <p>首页顶部天气展示使用 temperature / weather / city；
 * 其余字段为扩展信息（风况、湿度、今日温差等），可留给后续页面使用。
 *
 * @author EnjoyIot
 */
@Data
public class WeatherRespVO {

    /** 城市 adcode 编码（区县级，天气按此编码返回） */
    private String adcode;

    /** 省份（实况天气返回） */
    private String province;

    /** 城市/区域名称（实况天气返回，如 广州市） */
    private String city;

    /** 实况天气现象，如 晴 / 多云 / 小雨 */
    private String weather;

    /** 实况温度（℃），高德返回字符串，如 28 */
    private String temperature;

    /** 实况风向，如 东南风 */
    private String windDirection;

    /** 实况风力，如 ≤3 级 */
    private String windPower;

    /** 实况湿度（%），如 76 */
    private String humidity;

    /** 实况发布时间，如 2026-09-03 10:00:00 */
    private String reportTime;

    /** 预报日期（今日），如 2026-09-03 */
    private String date;

    /** 今日白天天气现象 */
    private String dayWeather;

    /** 今日夜间天气现象 */
    private String nightWeather;

    /** 今日最高温（℃） */
    private String todayMax;

    /** 今日最低温（℃） */
    private String todayMin;
}
