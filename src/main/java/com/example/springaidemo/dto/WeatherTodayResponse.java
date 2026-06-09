package com.example.springaidemo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当天天气接口响应。
 */
@Data
@AllArgsConstructor
public class WeatherTodayResponse {

    /**
     * 城市名称。
     */
    private String city;

    /**
     * 查询日期。
     */
    private String date;

    /**
     * 天气描述。
     */
    private String condition;

    /**
     * 当前温度。
     */
    private int temperatureC;

    /**
     * 最低温度。
     */
    private int lowTemperatureC;

    /**
     * 最高温度。
     */
    private int highTemperatureC;

    /**
     * 湿度。
     */
    private int humidity;

    /**
     * 风向风力描述。
     */
    private String wind;

    /**
     * 穿衣或出行建议。
     */
    private String advice;

    /**
     * 数据来源描述。
     */
    private String source;
}
