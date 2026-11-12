package com.enjoyiot.module.eiot.controller.admin.weather;

import com.enjoyiot.framework.common.pojo.CommonResult;
import com.enjoyiot.module.eiot.controller.admin.weather.vo.WeatherRespVO;
import com.enjoyiot.module.eiot.service.weather.AmapWeatherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.enjoyiot.framework.common.pojo.CommonResult.success;

/**
 * 天气查询 Controller
 *
 * <p>首页顶部天气展示：由小程序端 {@code uni.getLocation} 定位后传入经纬度，
 * 后端经高德逆地理编码得到 adcode 再查询天气；定位缺失/解析失败时降级为
 * 默认城市（见 {@link AmapWeatherService#DEFAULT_ADCODE}）。
 *
 * @author EnjoyIot
 */
@Tag(name = "管理后台 - 天气查询")
@RestController
@RequestMapping("/eiot/weather")
@Validated
public class WeatherController {

    @Resource
    private AmapWeatherService weatherService;

    @GetMapping("/current")
    @Operation(summary = "获取当前位置天气（实况 + 今日预报）")
    public CommonResult<WeatherRespVO> getCurrentWeather(
            @RequestParam(value = "longitude", required = false)
            @Parameter(description = "经度，小程序 uni.getLocation 定位获取") Double longitude,
            @RequestParam(value = "latitude", required = false)
            @Parameter(description = "纬度，小程序 uni.getLocation 定位获取") Double latitude) {
        return success(weatherService.getWeather(resolveAdcode(longitude, latitude)));
    }

    /**
     * 解析查询天气所需的城市 adcode
     *
     * <p>优先级：小程序定位经纬度 → 逆地理编码；失败/未传则使用默认城市。
     */
    private String resolveAdcode(Double longitude, Double latitude) {
        // 1. 小程序定位经纬度 → 逆地理编码
        if (longitude != null && latitude != null) {
            AmapWeatherService.LocationInfo location = weatherService.regeoLocation(longitude, latitude);
            if (location != null) {
                return location.getAdcode();
            }
        }
        // 2. 兜底默认城市
        return AmapWeatherService.DEFAULT_ADCODE;
    }
}
