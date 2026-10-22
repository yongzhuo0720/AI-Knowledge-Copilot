package com.aicopilot.health;

import com.aicopilot.user.AuthenticationInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * {@code @WebMvcTest} 切片会把 {@code WebMvcConfigurer} 一并装配（它属于 MVC 基础设施），
     * 而 {@code AuthenticationWebConfiguration} 依赖 {@code AuthenticationInterceptor}。
     * 拦截器背后是 MySQL 与 Redis，切片里没有这些基础设施，所以用替身顶上。
     *
     * <p>健康检查路径已被 {@code excludePathPatterns} 排除在拦截范围之外，因此这个替身
     * 在本测试中不会真的被调用 —— 它只是为了让 Context 能装配起来。
     */
    @MockitoBean
    private AuthenticationInterceptor authenticationInterceptor;

    @Test
    void returnsHealthyStatus() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }
}
