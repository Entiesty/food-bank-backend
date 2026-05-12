package com.foodbank.dispatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 🚨 终极杀手锏：强制要求测试环境启动一个真实的随机端口 Tomcat！
// 这样 WebSocket 就能找到它需要的 ServerContainer，绝对不会再报错了！
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoodBankBackendApplicationTests {

    @Test
    void contextLoads() {
    }

}