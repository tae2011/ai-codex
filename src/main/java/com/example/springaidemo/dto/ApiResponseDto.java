package com.example.springaidemo.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

@Data
public class ApiResponseDto {
    private String message;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;
    
    private String type;
    private String id;

    /**
     * 创建默认响应对象，并初始化响应时间。
     */
    public ApiResponseDto() {
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 创建带消息、类型和唯一 ID 的响应对象。
     *
     * @param message 响应消息
     * @param type 响应类型
     * @param id 响应 ID
     */
    public ApiResponseDto(String message, String type, String id) {
        this();
        this.message = message;
        this.type = type;
        this.id = id;
    }
}
