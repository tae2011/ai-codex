package com.example.springaidemo.controller;

import com.example.springaidemo.dto.WeatherTodayResponse;
import com.example.springaidemo.service.WeatherToolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供天气工具接口示例。
 */
@Slf4j
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/tools/weather")
public class WeatherToolController {

    private final WeatherToolService weatherToolService;

    /**
     * 注入天气工具服务。
     *
     * @param weatherToolService 天气工具服务
     */
    public WeatherToolController(WeatherToolService weatherToolService) {
        this.weatherToolService = weatherToolService;
    }

    /**
     * 根据城市名称获取当天的天气信息。
     *
     * @param city 城市名称
     * @return 当天天气
     */
    @GetMapping("/today")
    public WeatherTodayResponse getTodayWeather(@RequestParam String city) {
        log.info("weather tool request received, city={}", city);
        return weatherToolService.getTodayWeather(city);
    }
}
