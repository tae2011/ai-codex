package com.example.springaidemo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpringaidemoApplicationTests {

    /**
     * 验证 Spring Boot 上下文可以正常启动。
     */
    @Test
    void contextLoads() {
        System.out.println(123);
    }

}
