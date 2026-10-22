package com.aicopilot.user;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.aicopilot.common.api.ApiResponse;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "账号与登录", description = "注册、登录（含管理员登录分流）、登出（真失效）")
public class UserController {

    private static final String REGISTER_BUCKET = "register";

    private final UserService userService;
    private final AuthenticationService authenticationService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public UserController(
            UserService userService,
            AuthenticationService authenticationService,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties
    ) {
        this.userService = userService;
        this.authenticationService = authenticationService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 注册按客户端 IP 限流：这是唯一一个不登录也能写库的入口，
     * 不设限就允许无限建号（进而无限占用 ask 配额）。
     *
     * <p>优先取 nginx 覆写的 {@code X-Real-IP}（$remote_addr），
     * 不回退到 X-Forwarded-For —— 后者是「追加」语义，第一段由客户端控制，可伪造。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest httpRequest
    ) {
        rateLimiter.consume(REGISTER_BUCKET, clientIp(httpRequest), rateLimitProperties.getRegister());
        return ApiResponse.success(userService.register(request));
    }

    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp.trim();
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authenticationService.login(request));
    }

    /**
     * 登出：从 Redis 里真正删掉 token。
     *
     * <p>为什么必须有这个接口：如果登出只是前端清 localStorage，Redis 里的 token
     * 在 TTL（8 小时）到期前依然是有效的。别人拿到这个串照样能调接口——
     * 在公用电脑上登出等于没登出。
     */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(Map.of("revoked", authenticationService.logout(authorization)));
    }
}
