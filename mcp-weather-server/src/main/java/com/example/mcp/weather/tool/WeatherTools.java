package com.example.mcp.weather.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 调用 wttr.in 免费天气 API 获取实时天气信息
 */
@Service
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);

    @Tool(description = "查询指定城市的实时天气信息，包括温度、湿度、风速、天气描述等。支持中英文城市名，如 Beijing、上海、Tokyo。")
    public String getWeather(
            @ToolParam(description = "城市名称，如 Beijing、上海、Tokyo") String city) {

        log.info("[WeatherTools] Querying weather for: {}", city);

        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String urlStr = "https://wttr.in/" + encodedCity + "?format=j1";

            URI uri = URI.create(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "spring-ai-mcp-server/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return parseWeatherJson(city, sb.toString());
            } else {
                return "无法获取「" + city + "」的天气信息，API 返回状态码: " + responseCode;
            }
        } catch (Exception e) {
            log.error("[WeatherTools] Error: {}", e.getMessage());
            return "查询「" + city + "」天气时出错: " + e.getMessage();
        }
    }

    private String parseWeatherJson(String city, String json) {
        try {
            String temp = extractJsonValue(json, "temp_C");
            String feelsLike = extractJsonValue(json, "FeelsLikeC");
            String humidity = extractJsonValue(json, "humidity");
            String windSpeed = extractJsonValue(json, "windspeedKmph");
            String windDir = extractJsonValue(json, "winddir16Point");
            String weatherDesc = extractJsonArrayValue(json, "weatherDesc", "value");
            String visibility = extractJsonValue(json, "visibility");
            String pressure = extractJsonValue(json, "pressure");

            StringBuilder result = new StringBuilder();
            result.append("城市: ").append(city).append("\n");
            result.append("温度: ").append(temp).append("°C");
            if (feelsLike != null) result.append("（体感 ").append(feelsLike).append("°C）");
            result.append("\n");
            if (weatherDesc != null) result.append("天气: ").append(weatherDesc).append("\n");
            result.append("湿度: ").append(humidity).append("%\n");
            result.append("风速: ").append(windSpeed).append(" km/h");
            if (windDir != null) result.append("，风向: ").append(windDir);
            result.append("\n");
            if (visibility != null) result.append("能见度: ").append(visibility).append(" km\n");
            if (pressure != null) result.append("气压: ").append(pressure).append(" hPa\n");
            return result.toString();
        } catch (Exception e) {
            return "城市: " + city + "\n原始天气数据: " + json.substring(0, Math.min(json.length(), 500));
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(":", idx + searchKey.length());
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private String extractJsonArrayValue(String json, String arrayKey, String valueKey) {
        String searchKey = "\"" + arrayKey + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;
        int searchEnd = Math.min(idx + 200, json.length());
        String sub = json.substring(idx, searchEnd);
        return extractJsonValue(sub, valueKey);
    }
}
