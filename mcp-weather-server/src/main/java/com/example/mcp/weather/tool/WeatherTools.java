package com.example.mcp.weather.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP Server 工具：天气查询
 * 调用阿里云市场天气 API（APPCODE 鉴权）获取天气信息
 */
@Service
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${weather.api.host:https://tinaqi.market.alicloudapi.com}")
    private String apiHost;

    @Value("${weather.api.path:/area-to-weather-date}")
    private String apiPath;

    @Value("${weather.api.appcode:}")
    private String appCode;

    @Value("${weather.api.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${weather.api.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Tool(description = "查询指定城市的实时天气信息，包括温度、湿度、风速、天气描述等。支持中英文城市名，如 Beijing、上海、Tokyo。")
    public String getWeather(
            @ToolParam(description = "城市名称，如 Beijing、上海、Tokyo") String city) {

        return queryAliyunWeather(city, false);
    }

    @Tool(description = "查询指定城市未来3天的天气预报，包括每天的最高温、最低温、天气描述、降水概率等。支持中英文城市名，如 Beijing、上海、Tokyo。适用于用户询问明天、后天或未来几天天气的场景。")
    public String getWeatherForecast(
            @ToolParam(description = "城市名称，如 Beijing、上海、Tokyo") String city) {

        return queryAliyunWeather(city, true);
    }

    private String queryAliyunWeather(String city, boolean forecast) {
        if (city == null || city.isBlank()) {
            return "请提供要查询的城市名称。";
        }
        String resolvedAppCode = resolveAppCode();
        if (resolvedAppCode == null || resolvedAppCode.isBlank()) {
            return "天气服务未配置 AppCode，请设置环境变量 WEATHER_API_APPCODE。";
        }

        log.info("[WeatherTools] Query aliyun weather, city={}, forecast={}", city, forecast);

        Map<String, String> querys = new LinkedHashMap<>();
        querys.put("area", city.trim());
        querys.put("date", LocalDate.now().format(DATE_FORMATTER));
        querys.put("need3HourForcast", forecast ? "1" : "0");

        try {
            ApiResponse apiResponse = executeRequest(querys, resolvedAppCode);
            if (apiResponse.statusCode() != 200) {
                return "无法获取「" + city + "」天气信息，API 状态码: " + apiResponse.statusCode()
                        + "\n错误详情: " + truncate(apiResponse.body(), 400);
            }
            return formatAliyunResponse(city, apiResponse.body(), forecast);
        } catch (Exception e) {
            log.error("[WeatherTools] Aliyun weather query failed, city={}, forecast={}", city, forecast, e);
            return "查询「" + city + "」天气时出错: " + e.getMessage();
        }
    }

    private ApiResponse executeRequest(Map<String, String> querys, String resolvedAppCode) throws Exception {
        String query = querys.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        String normalizedHost = apiHost.endsWith("/") ? apiHost.substring(0, apiHost.length() - 1) : apiHost;
        String normalizedPath = apiPath.startsWith("/") ? apiPath : "/" + apiPath;
        URI uri = URI.create(normalizedHost + normalizedPath + (query.isEmpty() ? "" : "?" + query));

        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "APPCODE " + resolvedAppCode.trim());
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);

        int statusCode = conn.getResponseCode();
        String body;
        if (statusCode >= 200 && statusCode < 300) {
            body = readAll(conn.getInputStream());
        } else {
            body = conn.getErrorStream() != null ? readAll(conn.getErrorStream()) : "";
        }
        return new ApiResponse(statusCode, body);
    }

    private String resolveAppCode() {
        if (appCode != null && !appCode.isBlank()) {
            return appCode;
        }
        String fromEnv = System.getenv("WEATHER_API_APPCODE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return null;
    }

    private String readAll(java.io.InputStream inputStream) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String formatAliyunResponse(String city, String json, boolean forecast) {
        if (json == null || json.isBlank()) {
            return "城市: " + city + "\n天气服务返回空结果。";
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            int showApiCode = root.path("showapi_res_code").asInt(0);
            JsonNode bodyNode = root.path("showapi_res_body");
            int retCode = bodyNode.path("ret_code").asInt(0);

            if (showApiCode != 0 || retCode != 0) {
                String err = root.path("showapi_res_error").asText("");
                if (err.isBlank()) {
                    err = bodyNode.path("remark").asText("天气服务返回异常");
                }
                return "查询「" + city + "」天气失败: " + err;
            }

            JsonNode cityInfo = bodyNode.path("cityInfo");
            JsonNode dayNode = bodyNode.path("f1");

            String cityName = textOr(cityInfo.path("c5"), city);
            String province = textOr(cityInfo.path("c7"), null);
            String date = textOr(dayNode.path("day"), textOr(bodyNode.path("date"), null));
            String dayWeather = textOr(dayNode.path("day_weather"), null);
            String nightWeather = textOr(dayNode.path("night_weather"), null);
            String dayTemp = textOr(dayNode.path("day_air_temperature"), null);
            String nightTemp = textOr(dayNode.path("night_air_temperature"), null);
            String dayWindDir = textOr(dayNode.path("day_wind_direction"), null);
            String dayWindPower = textOr(dayNode.path("day_wind_power"), null);
            String humidity = textOr(dayNode.path("sd"), null);
            String pressure = textOr(dayNode.path("air_press"), null);
            String rainProb = textOr(dayNode.path("jiangshui"), null);
            String uv = textOr(dayNode.path("ziwaixian"), null);
            String clothes = textOr(dayNode.path("index").path("clothes").path("title"), null);
            String comfort = textOr(dayNode.path("index").path("comfort").path("title"), null);

            StringBuilder result = new StringBuilder();
            result.append("城市: ").append(cityName);
            if (province != null) {
                result.append("（").append(province).append("）");
            }
            result.append("\n");
            result.append("数据源: 阿里云天气 API\n");
            if (date != null) {
                result.append("日期: ").append(date).append("\n");
            }

            if (dayWeather != null || nightWeather != null) {
                result.append("天气: ").append(dayWeather != null ? dayWeather : "-")
                        .append(" / 夜间 ").append(nightWeather != null ? nightWeather : "-")
                        .append("\n");
            }
            if (dayTemp != null || nightTemp != null) {
                result.append("气温: ").append(nightTemp != null ? nightTemp : "?")
                        .append("~")
                        .append(dayTemp != null ? dayTemp : "?")
                        .append("°C\n");
            }
            if (dayWindDir != null || dayWindPower != null) {
                result.append("风况: ")
                        .append(dayWindDir != null ? dayWindDir : "")
                        .append(dayWindPower != null ? " " + dayWindPower : "")
                        .append("\n");
            }
            if (humidity != null) result.append("湿度: ").append(humidity).append("\n");
            if (pressure != null) result.append("气压: ").append(pressure).append("\n");
            if (rainProb != null) result.append("降水概率: ").append(rainProb).append("\n");
            if (uv != null) result.append("紫外线: ").append(uv).append("\n");
            if (clothes != null) result.append("穿衣建议: ").append(clothes).append("\n");
            if (comfort != null) result.append("体感舒适度: ").append(comfort).append("\n");

            if (forecast) {
                List<String> lines = extract3HourForecastLines(dayNode.path("3hourForcast"), 4);
                if (!lines.isEmpty()) {
                    result.append("\n未来分时预报:\n");
                    for (String line : lines) {
                        result.append("- ").append(line).append("\n");
                    }
                }
            }

            if (result.toString().lines().count() <= 4) {
                result.append("原始数据片段: ").append(truncate(json, 500));
            }
            return result.toString().trim();
        } catch (Exception e) {
            log.warn("[WeatherTools] Parse aliyun weather response failed: {}", e.getMessage());
            return "城市: " + city + "\n原始天气数据: " + truncate(json, 500);
        }
    }

    private List<String> extract3HourForecastLines(JsonNode forecastArray, int limit) {
        List<String> lines = new ArrayList<>();
        if (forecastArray == null || !forecastArray.isArray()) {
            return lines;
        }

        int count = 0;
        for (JsonNode node : forecastArray) {
            if (count++ >= limit) {
                break;
            }
            String hour = textOr(node.path("hour"), null);
            String weather = textOr(node.path("weather"), null);
            String temp = textOr(node.path("temperature"), null);
            String min = textOr(node.path("temperature_min"), null);
            String max = textOr(node.path("temperature_max"), null);
            String windDir = textOr(node.path("wind_direction"), null);
            String windPower = textOr(node.path("wind_power"), null);

            StringBuilder sb = new StringBuilder();
            if (hour != null) sb.append(hour).append(" ");
            if (weather != null) sb.append(weather).append(" ");
            if (temp != null) sb.append(temp).append("°C ");
            if (min != null || max != null) {
                sb.append("(").append(min != null ? min : "?")
                        .append("~")
                        .append(max != null ? max : "?")
                        .append("°C) ");
            }
            if (windDir != null || windPower != null) {
                sb.append(windDir != null ? windDir : "")
                        .append(windPower != null ? " " + windPower : "");
            }
            String text = sb.toString().trim();
            if (!text.isBlank()) {
                lines.add(text);
            }
        }
        return lines;
    }

    private String textOr(JsonNode node, String defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }

    private record ApiResponse(int statusCode, String body) {
    }
}
