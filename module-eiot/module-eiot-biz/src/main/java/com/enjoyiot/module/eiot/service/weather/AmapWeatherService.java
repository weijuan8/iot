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
package com.enjoyiot.module.eiot.service.weather;

import com.enjoyiot.framework.common.exception.ServiceException;
import com.enjoyiot.module.eiot.api.enums.ErrorCodeConstants;
import com.enjoyiot.module.eiot.controller.admin.weather.vo.WeatherRespVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 高德地图天气查询工具（纯 GET 直连）
 *
 * <p>对接官方「天气查询」Web 服务 API：
 * <a href="https://lbs.amap.com/api/webservice/guide/api/weatherinfo/">官方文档</a>，
 * 请求方式 GET，参数仅 key / city(adcode) / extensions / output，无需签名：
 * <pre>
 *   https://restapi.amap.com/v3/weather/weatherInfo?key=xx&city=440100&extensions=all
 * </pre>
 *
 * <p>注意：key 必须为高德控制台申请的「Web 服务」平台类型 Key（见 {@link #AMAP_KEY}）；
 * 若用 Web(JS API)/微信小程序 等平台 Key 调用本接口会返回 USERKEY_PLAT_NOMATCH。
 *
 * <p>小程序定位拿到的是经纬度，而天气接口只认城市 adcode，因此提供
 * {@link #regeoLocation(double, double)}（逆地理编码，经纬度 → adcode）做一次转换。
 *
 * @author EnjoyIot
 */
@Slf4j
@Service
public class AmapWeatherService {

    /** 高德 Web 服务 API 域名 */
    private static final String AMAP_BASE_URL = "https://restapi.amap.com";

    /** 天气查询 API 路径 */
    private static final String WEATHER_PATH = "/v3/weather/weatherInfo";

    /** 逆地理编码 API 路径（经纬度 → adcode） */
    private static final String REGEO_PATH = "/v3/geocode/regeo";

    /** 实况天气（base）：返回 lives（实况）数据 */
    private static final String EXTENSIONS_BASE = "base";

    /** 预报天气（all）：返回 forecasts（预报）数据 */
    private static final String EXTENSIONS_ALL = "all";

    /**
     * 高德地图 Web 服务 Key
     *
     * <p>高德控制台 → 应用管理 → 添加 Key → 服务平台选「Web服务」后生成。
     * 当前 Key 明文直连即可（控制台未绑定安全密钥，无需 sig 签名）。
     */
    public static final String AMAP_KEY = "94a9203b19ee3343490e117f57a17aa6";

    /** 小程序定位/逆地理解析失败时的兜底 adcode（默认：广州市），可按部署所在地修改 */
    public static final String DEFAULT_ADCODE = "440100";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 获取天气（实况 + 今日预报），供首页顶部展示
     *
     * <p>注意：高德官方文档中 extensions 为二选一——{@code base} 只返回实况(lives)，
     * {@code all} 只返回预报(forecasts)，一次请求拿不到全部，故这里分两次请求：
     * <ol>
     *     <li>extensions=base：实况天气（首页核心，温度/天气/湿度/风力等）</li>
     *     <li>extensions=all：今日预报（白天/夜间天气、最高/最低温），失败不影响实况</li>
     * </ol>
     *
     * @param adcode 城市 adcode 编码（区县级，如广州 440100）
     * @return 组合天气信息
     */
    public WeatherRespVO getWeather(String adcode) {
        // 1. 实况天气（extensions=base → lives）
        JsonNode baseRoot = request(adcode, EXTENSIONS_BASE);
        JsonNode live = baseRoot.path("lives").path(0);
        if (live.isMissingNode() || live.isNull()) {
            log.error("高德天气响应缺少实况(lives)数据，adcode: {}, 原始响应: {}", adcode, truncate(baseRoot.toString()));
            throw new ServiceException(ErrorCodeConstants.WEATHER_QUERY_FAILED);
        }
        WeatherRespVO vo = new WeatherRespVO();
        vo.setAdcode(getText(live, "adcode", adcode));
        vo.setProvince(getText(live, "province", null));
        vo.setCity(getText(live, "city", null));
        vo.setWeather(getText(live, "weather", null));
        vo.setTemperature(getText(live, "temperature", null));
        vo.setWindDirection(getText(live, "winddirection", null));
        vo.setWindPower(getText(live, "windpower", null));
        vo.setHumidity(getText(live, "humidity", null));
        vo.setReportTime(getText(live, "reporttime", null));
        // 2. 今日预报（extensions=all → forecasts，casts[0] 为今天），失败仅告警不拖垮实况
        try {
            JsonNode allRoot = request(adcode, EXTENSIONS_ALL);
            JsonNode cast = allRoot.path("forecasts").path(0).path("casts").path(0);
            if (!cast.isMissingNode() && !cast.isNull()) {
                vo.setDate(getText(cast, "date", null));
                vo.setDayWeather(getText(cast, "dayweather", null));
                vo.setNightWeather(getText(cast, "nightweather", null));
                vo.setTodayMax(getText(cast, "daytemp", null));
                vo.setTodayMin(getText(cast, "nighttemp", null));
            }
        } catch (ServiceException e) {
            log.warn("高德预报天气(all)查询失败，忽略预报数据，仅返回实况，adcode: {}", adcode);
        }
        return vo;
    }

    /**
     * 经纬度 → 逆地理编码，解析 adcode
     *
     * <p>小程序 {@code uni.getLocation} 定位得到经纬度后，天气接口需要的是 adcode，
     * 故先经此接口转换；key 未开通逆地理编码服务或解析失败时返回 null，由调用方降级。
     *
     * @param longitude 经度
     * @param latitude  纬度
     * @return 定位解析结果；解析失败返回 null
     */
    public LocationInfo regeoLocation(double longitude, double latitude) {
        String url = AMAP_BASE_URL + REGEO_PATH + "?key=" + AMAP_KEY
                + "&location=" + longitude + "," + latitude;
        JsonNode root = get(url);
        if (root == null) {
            return null;
        }
        try {
            JsonNode component = root.path("regeocode").path("addressComponent");
            String adcode = component.path("adcode").asText(null);
            if (adcode == null || adcode.isEmpty()) {
                log.warn("高德逆地理编码未返回 adcode，location: {},{}，降级处理", longitude, latitude);
                return null;
            }
            String city = component.path("city").asText(null);
            if (city == null || city.isEmpty()) {
                // 直辖市等场景 city 为空数组，使用省份名兜底
                city = component.path("province").asText(null);
            }
            return new LocationInfo(adcode, city);
        } catch (Exception e) {
            log.error("高德逆地理编码解析异常，location: {},{}", longitude, latitude, e);
            return null;
        }
    }

    /**
     * 发起高德天气查询 GET 请求
     *
     * @param adcode     城市 adcode 编码
     * @param extensions base=实况(lives)，all=预报(forecasts)
     * @return 高德返回的根节点；查询失败时抛出业务异常
     */
    private JsonNode request(String adcode, String extensions) {
        String url = AMAP_BASE_URL + WEATHER_PATH + "?key=" + AMAP_KEY
                + "&city=" + adcode + "&extensions=" + extensions;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            // status=1 表示查询成功
            if (!"1".equals(root.path("status").asText())) {
                log.error("高德天气查询失败，url: {}, status: {}, info: {}, infocode: {}, 原始响应: {}",
                        url, root.path("status").asText(), root.path("info").asText(),
                        root.path("infocode").asText(), truncate(root.toString()));
                throw new ServiceException(ErrorCodeConstants.WEATHER_QUERY_FAILED);
            }
            return root;
        } catch (ServiceException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            log.error("高德天气查询异常，url: {}", url, e);
            Thread.currentThread().interrupt(); // 恢复中断状态
            throw new ServiceException(ErrorCodeConstants.WEATHER_QUERY_FAILED);
        }
    }

    /**
     * 发起高德 Web 服务 GET 请求（逆地理编码等辅助接口）
     *
     * <p>失败时不抛异常，返回 null 供调用方降级。
     *
     * @param url 完整请求地址
     * @return 根节点；请求失败或 status != 1 返回 null
     */
    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !"1".equals(root.path("status").asText())) {
                log.warn("高德逆地理编码失败，url: {}, status: {}, info: {}, 原始响应: {}",
                        url,
                        root == null ? null : root.path("status").asText(),
                        root == null ? null : root.path("info").asText(),
                        root == null ? null : truncate(root.toString()));
                return null;
            }
            return root;
        } catch (IOException | InterruptedException e) {
            log.error("高德逆地理编码异常，url: {}", url, e);
            Thread.currentThread().interrupt(); // 恢复中断状态
            return null;
        }
    }

    /**
     * 安全读取节点文本字段，避免字段缺失/为空的 NPE
     */
    private static String getText(JsonNode node, String field, String defaultValue) {
        String value = node.path(field).asText(null);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    /**
     * 截断过长的日志文本（高德原始响应），避免刷屏，最多保留 1500 字符
     */
    private static String truncate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 1500 ? text : text.substring(0, 1500) + "...[已截断]";
    }

    /**
     * 逆地理编码定位解析结果
     */
    @lombok.Getter
    public static class LocationInfo {

        /** 城市 adcode 编码（区县级） */
        private final String adcode;

        /** 城市名（市级或省级兜底，如 广州市 / 北京市） */
        private final String city;

        public LocationInfo(String adcode, String city) {
            this.adcode = adcode;
            this.city = city;
        }
    }
}
