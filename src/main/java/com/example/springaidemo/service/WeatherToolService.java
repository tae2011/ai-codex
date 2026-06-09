package com.example.springaidemo.service;

import com.example.springaidemo.dto.WeatherTodayResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * 提供一个可本地演示的天气工具服务。
 */
@Service
public class WeatherToolService {

    private static final Map<String, WeatherTemplate> BUILTIN_WEATHER = Map.of(
            "北京", new WeatherTemplate("晴转多云", 29, 22, 31, 38, "东北风 2级", "白天偏热，外出可带薄外套并注意补水。"),
            "上海", new WeatherTemplate("多云", 27, 24, 30, 66, "东南风 3级", "体感偏闷，建议穿轻薄衣物。"),
            "南京", new WeatherTemplate("小雨转阴", 26, 23, 28, 78, "东风 2级", "出门建议带伞，路面湿滑注意脚下。"),
            "杭州", new WeatherTemplate("阵雨", 25, 22, 27, 82, "东南风 2级", "可能随时有雨，建议带好雨具。"),
            "广州", new WeatherTemplate("雷阵雨", 30, 26, 33, 81, "南风 3级", "湿热明显，午后注意防雨和防晒。"),
            "深圳", new WeatherTemplate("多云转阵雨", 31, 27, 33, 79, "南风 3级", "天气闷热，空调房内外温差较大。")
    );

    /**
     * 根据城市名获取当天天气。
     *
     * @param city 城市名称
     * @return 当天天气
     */
    public WeatherTodayResponse getTodayWeather(String city) {
        String normalizedCity = normalizeCity(city);
        WeatherTemplate template = BUILTIN_WEATHER.get(normalizedCity);
        if (template == null) {
            template = buildFallbackTemplate(normalizedCity);
        }
        return new WeatherTodayResponse(
                normalizedCity,
                LocalDate.now().toString(),
                template.condition(),
                template.temperatureC(),
                template.lowTemperatureC(),
                template.highTemperatureC(),
                template.humidity(),
                template.wind(),
                template.advice(),
                "local-demo-weather"
        );
    }

    /**
     * 将天气对象格式化成适合直接回复用户的文本。
     *
     * @param weather 天气对象
     * @return 用户可读文本
     */
    public String toReadableText(WeatherTodayResponse weather) {
        return weather.getCity() + "今天天气：" + weather.getCondition()
                + "，当前约 " + weather.getTemperatureC() + "℃"
                + "，气温 " + weather.getLowTemperatureC() + "℃ - " + weather.getHighTemperatureC() + "℃"
                + "，湿度 " + weather.getHumidity() + "%"
                + "，" + weather.getWind()
                + "。建议：" + weather.getAdvice();
    }

    /**
     * 归一化城市名称。
     *
     * @param city 原始城市名
     * @return 标准化城市名
     */
    private String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city 不能为空");
        }
        String normalized = city.trim()
                .replace("市", "")
                .replace("省", "")
                .replace("特别行政区", "")
                .replace("自治区", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("city 不能为空");
        }
        return normalized;
    }

    /**
     * 对未内置的城市生成一个稳定的示例天气。
     *
     * @param city 城市名称
     * @return 回退天气模板
     */
    private WeatherTemplate buildFallbackTemplate(String city) {
        int seed = Math.abs(city.toLowerCase(Locale.ROOT).hashCode());
        String[] conditions = {"晴", "多云", "阴", "小雨", "阵雨"};
        String[] winds = {"东北风 2级", "东风 2级", "西南风 3级", "北风 2级", "东南风 3级"};
        String[] advices = {
                "天气较平稳，适合外出。",
                "请根据温度变化增减衣物。",
                "紫外线可能偏强，外出注意防晒。",
                "如有降雨，建议携带雨具。",
                "空气略显闷热，注意补水。"
        };
        int low = 18 + seed % 8;
        int high = low + 4 + seed % 6;
        int current = Math.min(high, low + 2 + seed % 4);
        int humidity = 40 + seed % 45;
        return new WeatherTemplate(
                conditions[seed % conditions.length],
                current,
                low,
                high,
                humidity,
                winds[seed % winds.length],
                advices[seed % advices.length]
        );
    }

    /**
     * 天气模板。
     */
    private record WeatherTemplate(String condition,
                                   int temperatureC,
                                   int lowTemperatureC,
                                   int highTemperatureC,
                                   int humidity,
                                   String wind,
                                   String advice) {
    }
}
