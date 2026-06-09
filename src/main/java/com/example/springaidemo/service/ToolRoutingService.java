package com.example.springaidemo.service;

import com.example.springaidemo.dto.ChatMessageDto;
import com.example.springaidemo.dto.ToolCallResult;
import com.example.springaidemo.dto.WeatherTodayResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责根据用户问题路由到具体工具。
 */
@Service
public class ToolRoutingService {

    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            "(?<city>[\\p{IsHan}]{2,12}?)(今天|今日|现在|当前)?(的)?(天气|气温|温度)(怎么样|如何|咋样|呢|情况)?"
    );

    private final WeatherToolService weatherToolService;

    /**
     * 注入工具服务。
     *
     * @param weatherToolService 天气工具服务
     */
    public ToolRoutingService(WeatherToolService weatherToolService) {
        this.weatherToolService = weatherToolService;
    }

    /**
     * 根据最新用户消息尝试执行工具。
     *
     * @param messages 消息列表
     * @return 工具调用结果
     */
    public ToolCallResult tryHandle(List<ChatMessageDto> messages) {
        ChatMessageDto latestUserMessage = latestUserMessage(messages);
        if (latestUserMessage == null || latestUserMessage.getContent() == null) {
            return ToolCallResult.notHandled();
        }

        String content = latestUserMessage.getContent().trim();
        String city = extractWeatherCity(content);
        if (city == null) {
            return ToolCallResult.notHandled();
        }

        WeatherTodayResponse weather = weatherToolService.getTodayWeather(city);
        return ToolCallResult.handled(
                "weather.today",
                weatherToolService.toReadableText(weather),
                weather
        );
    }

    /**
     * 获取最后一条用户消息。
     *
     * @param messages 消息列表
     * @return 最新用户消息
     */
    private ChatMessageDto latestUserMessage(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto message = messages.get(i);
            if (message != null && "user".equalsIgnoreCase(message.getRole())) {
                return message;
            }
        }
        return null;
    }

    /**
     * 从天气类问题中提取城市名。
     *
     * @param content 用户问题
     * @return 城市名，未命中时返回 null
     */
    private String extractWeatherCity(String content) {
        Matcher matcher = WEATHER_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group("city");
        }
        return null;
    }
}
