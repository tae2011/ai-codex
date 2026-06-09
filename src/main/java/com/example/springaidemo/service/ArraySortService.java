package com.example.springaidemo.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 数组排序服务，提供数组排序相关业务逻辑。
 */
@Service
public class ArraySortService {

    /**
     * 对 int 数组进行从大到小排序，并保持原数组不变。
     *
     * @param arr 待排序数组
     * @return 排序后的新数组
     */
    public int[] sortDescending(int[] arr) {
        if (arr == null) {
            throw new IllegalArgumentException("数组不能为 null");
        }
        if (arr.length == 0) {
            return new int[0];
        }
        int[] result = Arrays.copyOf(arr, arr.length);
        Arrays.sort(result);
        reverseArray(result);
        return result;
    }

    /**
     * 原地反转数组元素。
     *
     * @param arr 待反转数组
     */
    private void reverseArray(int[] arr) {
        int left = 0;
        int right = arr.length - 1;
        while (left < right) {
            int temp = arr[left];
            arr[left] = arr[right];
            arr[right] = temp;
            left++;
            right--;
        }
    }
}
