package com.example.springaidemo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Function calling 风格的工具调用结果。
 */
@Data
@AllArgsConstructor
public class ToolCallResult {

    /**
     * 是否命中某个工具。
     */
    private boolean handled;

    /**
     * 工具名称。
     */
    private String toolName;

    /**
     * 返回给用户的文本。
     */
    private String content;

    /**
     * 工具输出对象。
     */
    private Object payload;

    /**
     * 构造一个未命中工具的结果。
     *
     * @return 未命中结果
     */
    public static ToolCallResult notHandled() {
        return new ToolCallResult(false, null, null, null);
    }

    /**
     * 构造一个已命中工具的结果。
     *
     * @param toolName 工具名称
     * @param content 返回给用户的文本
     * @param payload 工具输出对象
     * @return 已命中结果
     */
    public static ToolCallResult handled(String toolName, String content, Object payload) {
        return new ToolCallResult(true, toolName, content, payload);
    }
}
