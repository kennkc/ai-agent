package com.agent.session.orchestration;

import com.agent.session.controller.SessionController;
import com.agent.session.fsm.SessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 装配约束测试（运行态缺陷回归防护）。
 *
 * 背景：Phase 4 给 {@code SessionController} 追加了大脑层依赖后，类里出现**三个构造**，
 * 且 {@code SessionStore} 不是 Bean —— 编译和单元测试都照常通过，但
 * **Spring 启动直接失败**（`No default constructor found` / 依赖无法解析）。
 * 这类缺陷只有真正启动应用才会暴露，故在此用反射把它固化成断言。
 */
class SessionWiringTest {

    @Test
    void controllerHasExactlyOneAutowiredConstructor() {
        long count = Arrays.stream(SessionController.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .count();
        assertEquals(1, count,
                "SessionController 有多个构造时必须且只能有一个标注 @Autowired，否则 Spring 无法选择");
    }

    @Test
    void sessionStoreIsSpringComponentWithSingleAutowiredConstructor() {
        assertTrue(SessionStore.class.isAnnotationPresent(Component.class),
                "SessionStore 必须由 Spring 托管，否则控制器注入会失败");
        long count = Arrays.stream(SessionStore.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .count();
        assertEquals(1, count, "SessionStore 有多个构造，需显式指定注入构造");
    }
}
