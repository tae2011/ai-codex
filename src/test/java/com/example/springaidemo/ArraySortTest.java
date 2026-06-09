package com.example.springaidemo;

import com.example.springaidemo.service.ArraySortService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 数组排序服务测试。
 */
@Slf4j
@SpringBootTest
class ArraySortTest {

    @Autowired
    private ArraySortService arraySortService;

    /**
     * 为每个测试用例准备排序服务。
     */
    @BeforeEach
    void setUp() {
        if (arraySortService == null) {
            arraySortService = new ArraySortService();
        }
    }

    /**
     * 验证普通数组可以按从大到小排序。
     */
    @Test
    @DisplayName("测试正常数组排序")
    void testNormalArray() {
        int[] input = {3, 1, 4, 1, 5, 9, 2, 6};
        int[] expected = {9, 6, 5, 4, 3, 2, 1, 1};
        int[] result = arraySortService.sortDescending(input);

        log.info("输入数组：{}", Arrays.toString(input));
        log.info("排序后的数组：{}", Arrays.toString(result));

        assertArrayEquals(expected, result, "数组应该按从大到小排序");
    }

    /**
     * 验证已经降序的数组保持不变。
     */
    @Test
    @DisplayName("测试已排序数组")
    void testAlreadySorted() {
        int[] input = {9, 8, 7, 6, 5};
        int[] expected = {9, 8, 7, 6, 5};
        int[] result = arraySortService.sortDescending(input);
        assertArrayEquals(expected, result, "已排序的数组应该保持不变");
    }

    /**
     * 验证升序数组可以被反向排序。
     */
    @Test
    @DisplayName("测试升序数组")
    void testReverseSorted() {
        int[] input = {1, 2, 3, 4, 5};
        int[] expected = {5, 4, 3, 2, 1};
        int[] result = arraySortService.sortDescending(input);
        assertArrayEquals(expected, result, "升序数组应该被正确排序");
    }

    /**
     * 验证包含负数的数组可以正确排序。
     */
    @Test
    @DisplayName("测试包含负数的数组")
    void testArrayWithNegatives() {
        int[] input = {-5, 3, -1, 0, 2, -3};
        int[] expected = {3, 2, 0, -1, -3, -5};
        int[] result = arraySortService.sortDescending(input);
        assertArrayEquals(expected, result, "包含负数的数组应该正确排序");
    }

    /**
     * 验证单元素数组保持不变。
     */
    @Test
    @DisplayName("测试单个元素数组")
    void testSingleElement() {
        int[] input = {42};
        int[] expected = {42};
        int[] result = arraySortService.sortDescending(input);
        assertArrayEquals(expected, result, "单个元素数组应该保持不变");
    }

    /**
     * 验证空数组返回空结果。
     */
    @Test
    @DisplayName("测试空数组")
    void testEmptyArray() {
        int[] input = {};
        int[] result = arraySortService.sortDescending(input);
        assertNotNull(result, "空数组应该返回非 null 结果");
        assertEquals(0, result.length, "空数组应该返回空数组");
    }

    /**
     * 验证包含重复元素的数组可以正确排序。
     */
    @Test
    @DisplayName("测试包含重复元素的数组")
    void testArrayWithDuplicates() {
        int[] input = {5, 5, 5, 3, 3, 1};
        int[] expected = {5, 5, 5, 3, 3, 1};
        int[] result = arraySortService.sortDescending(input);
        assertArrayEquals(expected, result, "包含重复元素的数组应该正确排序");
    }

    /**
     * 验证排序方法不会修改原数组。
     */
    @Test
    @DisplayName("测试原数组不被修改")
    void testOriginalArrayNotModified() {
        int[] input = {3, 1, 4, 1, 5};
        int[] original = Arrays.copyOf(input, input.length);
        arraySortService.sortDescending(input);
        assertArrayEquals(original, input, "原数组不应该被修改");
    }

    /**
     * 验证 null 数组会抛出参数异常。
     */
    @Test
    @DisplayName("测试 null 数组抛出异常")
    void testNullArray() {
        assertThrows(IllegalArgumentException.class,
                () -> arraySortService.sortDescending(null),
                "null 数组应该抛出 IllegalArgumentException 异常");
    }
}
