package com.example.mcp.server.tool;

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

/**
 * MCP Server 工具：天气查询
 * 数据源：wttr.in（免费、无需 API Key、支持中英文城市名）
 */
@Service
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${weather.api.host:https://wttr.in}")
    private String apiHost;

    @Value("${weather.api.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${weather.api.read-timeout-ms:15000}")
    private int readTimeoutMs;

    @Tool(description = "查询指定城市的实时天气信息，包括温度、湿度、风速、天气描述等。支持中英文城市名，如 Beijing、上海、Tokyo。")
    public String getWeather(
            @ToolParam(description = "城市名称，如 Beijing、上海、Tokyo") String city) {
        return queryWttr(city, false, 0);
    }

    @Tool(description = "查询指定城市的天气预报。dayOffset=0 表示今天，1 表示明天（默认），2 表示后天。支持中英文城市名，如 Beijing、上海、Tokyo。")
    public String getWeatherForecast(
            @ToolParam(description = "城市名称，如 Beijing、上海、Tokyo") String city,
            @ToolParam(description = "预报天偏移：0=今天，1=明天（默认），2=后天。可为空") Integer dayOffset) {
        int resolved = dayOffset == null ? 1 : dayOffset;
        if (resolved < 0 || resolved > 2) {
            return "dayOffset 参数非法，请使用 0(今天)、1(明天) 或 2(后天)。";
        }
        return queryWttr(city, true, resolved);
    }

    private String queryWttr(String city, boolean forecast, int dayOffset) {
        if (city == null || city.isBlank()) {
            return "请提供要查询的城市名称。";
        }
        log.info("[WeatherTools] Query wttr.in, city={}, forecast={}, dayOffset={}", city, forecast, dayOffset);
        try {
            String encodedCity = URLEncoder.encode(city.trim(), StandardCharsets.UTF_8);
            String host = apiHost.endsWith("/") ? apiHost.substring(0, apiHost.length() - 1) : apiHost;
            URI uri = URI.create(host + "/" + encodedCity + "?format=j1&lang=zh");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "spring-ai-agent-weather/1.0");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            int status = conn.getResponseCode();
            String body = readAll(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (status != 200) {
                return "无法获取「" + city + "」天气信息，API 状态码: " + status + "\n错误详情: " + truncate(body, 400);
            }
            return formatWttr(city, body, forecast, dayOffset);
        } catch (Exception e) {
            log.error("[WeatherTools] wttr.in query failed, city={}, forecast={}, dayOffset={}", city, forecast, dayOffset, e);
            return "查询「" + city + "」天气时出错: " + e.getMessage();
        }
    }

    private String readAll(java.io.InputStream input) throws Exception {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String formatWttr(String city, String json, boolean forecast, int dayOffset) {
        if (json == null || json.isBlank()) {
            return "城市: " + city + "\n天气服务返回空结果。";
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode nearestAreaArr = root.path("nearest_area");
            JsonNode nearestArea = nearestAreaArr.isArray() && nearestAreaArr.size() > 0 ? nearestAreaArr.get(0) : null;
            String cityName = nearestArea != null ? firstValue(nearestArea.path("areaName"), city) : city;
            String region = nearestArea != null ? firstValue(nearestArea.path("region"), null) : null;
            String country = nearestArea != null ? firstValue(nearestArea.path("country"), null) : null;

            StringBuilder result = new StringBuilder();
            result.append("城市: ").append(cityName);
            if (region != null || country != null) {
                result.append("（");
                if (region != null) result.append(region);
                if (region != null && country != null) result.append(", ");
                if (country != null) result.append(country);
                result.append("）");
            }
            result.append("\n数据源: wttr.in\n");

            if (!forecast) {
                JsonNode curArr = root.path("current_condition");
                JsonNode cur = curArr.isArray() && curArr.size() > 0 ? curArr.get(0) : null;
                if (cur != null) {
                    String desc = firstValue(cur.path("lang_zh"), firstValue(cur.path("weatherDesc"), "未知"));
                    result.append("- 当前: 天气 ").append(desc)
                            .append("，气温 ").append(cur.path("temp_C").asText("?"))
                            .append("°C（体感 ").append(cur.path("FeelsLikeC").asText("?")).append("°C）")
                            .append("，湿度 ").append(cur.path("humidity").asText("?")).append("%")
                            .append("，风速 ").append(cur.path("windspeedKmph").asText("?")).append("km/h ")
                            .append(cur.path("winddir16Point").asText(""))
                            .append("\n");
                }
                return result.toString().trim();
            }

            JsonNode weatherArr = root.path("weather");
            if (!weatherArr.isArray() || weatherArr.size() <= dayOffset) {
                return result.append("暂无对应日期的预报数据").toString();
            }
            String targetLabel = dayOffset == 0 ? "今日" : dayOffset == 1 ? "明天" : "后天";
            result.append("目标日期预报:\n");
            appendDay(result, targetLabel, weatherArr.get(dayOffset));
            result.append("\n未来三天参考:\n");
            for (int i = 0; i < Math.min(3, weatherArr.size()); i++) {
                String label = i == 0 ? "今日" : i == 1 ? "明天" : "后天";
                appendDay(result, label, weatherArr.get(i));
            }
            return result.toString().trim();
        } catch (Exception e) {
            log.warn("[WeatherTools] parse wttr.in response failed: {}", e.getMessage());
            return "城市: " + city + "\n原始天气数据: " + truncate(json, 500);
        }
    }

    private void appendDay(StringBuilder result, String label, JsonNode dayNode) {
        if (dayNode == null || dayNode.isMissingNode() || dayNode.isNull()) {
            return;
        }
        String date = dayNode.path("date").asText("");
        String minT = dayNode.path("mintempC").asText("?");
        String maxT = dayNode.path("maxtempC").asText("?");
        String avgT = dayNode.path("avgtempC").asText("?");
        String sunHour = dayNode.path("sunHour").asText("?");

        String midDesc = "未知";
        String windKmph = "?";
        String windDir = "";
        JsonNode hourly = dayNode.path("hourly");
        if (hourly.isArray() && hourly.size() > 0) {
            JsonNode mid = hourly.size() > 4 ? hourly.get(4) : hourly.get(hourly.size() / 2);
            midDesc = firstValue(mid.path("lang_zh"), firstValue(mid.path("weatherDesc"), "未知"));
            windKmph = mid.path("windspeedKmph").asText("?");
            windDir = mid.path("winddir16Point").asText("");
        }
        result.append("- ").append(label);
        if (!date.isBlank()) result.append("(").append(date).append(")");
        result.append(": 天气 ").append(midDesc)
                .append("，气温 ").append(minT).append("-").append(maxT).append("°C（均温 ").append(avgT).append("°C）")
                .append("，日照 ").append(sunHour).append("h")
                .append("，风速 ").append(windKmph).append("km/h ").append(windDir)
                .append("\n");
    }

    private String firstValue(JsonNode arrayNode, String defaultValue) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.size() == 0) {
            return defaultValue;
        }
        JsonNode first = arrayNode.get(0);
        String text = first.path("value").asText("");
        return text.isBlank() ? defaultValue : text;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }
}
