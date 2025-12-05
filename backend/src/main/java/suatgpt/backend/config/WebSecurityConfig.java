package suatgpt.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.Collections;

/**
 * Spring Security 配置类
 * @Configuration: 声明该类是一个配置类
 * @EnableWebSecurity: 启用 Spring Security 的 Web 安全支持
 */
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    // 注入自定义的 JWT Token 过滤器，用于验证请求中的 JWT
    private final JwtTokenFilter jwtTokenFilter;

    // 从配置文件中读取 CORS 允许的来源 (例如: http://localhost:3000)
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;
    // 从配置文件中读取 CORS 允许的 HTTP 方法 (例如: GET, POST, PUT, DELETE)
    @Value("${cors.allowed-methods}")
    private String allowedMethods;
    // 从配置文件中读取 CORS 允许的请求头 (例如: Authorization, Content-Type)
    @Value("${cors.allowed-headers}")
    private String allowedHeaders;

    /**
     * 构造函数，注入 JwtTokenFilter。
     * 使用 @Lazy 注解是为了避免在配置类初始化时，JwtTokenFilter 尚未完全初始化导致的循环依赖问题。
     */
    public WebSecurityConfig(@Lazy JwtTokenFilter jwtTokenFilter) {
        this.jwtTokenFilter = jwtTokenFilter;
    }

    /**
     * 定义密码编码器 Bean。
     * 推荐使用 BCryptPasswordEncoder 进行密码加密存储和验证。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 暴露 AuthenticationManager Bean。
     * AuthenticationManager 负责处理认证请求，如用户名/密码登录。
     * @param authenticationConfiguration Spring Security 认证配置
     * @return 认证管理器
     * @throws Exception 获取过程中可能抛出的异常
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * 配置并创建 CorsFilter Bean，用于处理跨域资源共享 (CORS)。
     * 这是一个全局的 CORS 配置，早于 Spring Security 内部的 CORS 配置执行。
     */
    @Bean
    public CorsFilter corsFilter() {
        // 基于 URL 配置 CORS 策略
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // 允许发送 Cookie
        config.setAllowCredentials(true);
        // 设置允许的来源，通过逗号分隔字符串解析为列表
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        // 设置允许的 HTTP 方法
        config.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
        // 设置允许的请求头
        config.setAllowedHeaders(Arrays.asList(allowedHeaders.split(",")));
        // 允许客户端访问响应头中的 Authorization 字段 (用于 JWT 认证)
        config.setExposedHeaders(Collections.singletonList("Authorization"));
        // 预检请求 (Pre-flight) 的最大缓存时间 (秒)
        config.setMaxAge(3600L);

        // 对所有路径 ("/**") 应用此 CORS 配置
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * 配置 Spring Security 的安全过滤链。
     * 这是定义应用安全规则的核心方法。
     * @param http HttpSecurity 配置对象
     * @return 安全过滤链
     * @throws Exception 配置过程中可能抛出的异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF (Cross-Site Request Forgery) 保护，因为我们使用 JWT，这是一种无状态认证机制，不需要 CSRF 保护
            .csrf(AbstractHttpConfigurer::disable)
            
            // 配置 Spring Security 内部的 CORS 处理。
            // 虽然上面定义了 CorsFilter，但在 HttpSecurity 中也配置一遍是最佳实践。
            .cors(cors -> cors.configurationSource(request -> {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
                config.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
                config.setAllowedHeaders(Arrays.asList(allowedHeaders.split(",")));
                config.setAllowCredentials(true);
                return config;
            }))
            
            // 配置会话管理
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 由于使用 JWT，将 Session 创建策略设置为 STATELESS (无状态)，不会创建或使用 HTTP Session
            
            // 配置请求授权规则
            .authorizeHttpRequests(auth -> auth
                // 允许所有人访问 /api/auth/** 路径 (通常是注册、登录接口)
                .requestMatchers("/api/auth/**").permitAll()
                // 允许未登录用户访问 AI 聊天与诊断接口，以支持匿名提问
                .requestMatchers("/api/ai/chat").permitAll()
                .requestMatchers("/api/ai/test").permitAll()
                // 允许所有人访问 /h2-console/** 路径 (开发环境下的数据库控制台)
                .requestMatchers("/h2-console/**").permitAll()
                // 任何其他未匹配到的请求都必须经过认证 (即需要提供有效的 JWT)
                .anyRequest().authenticated()
            )
            
            // 配置请求头
            .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()))
            // 禁用 frameOptions 保护，这是为了允许 H2 Console 在 IFrame 中显示 (否则会被浏览器拦截)
            
            // 添加自定义的 JWT 过滤器
            // 将 jwtTokenFilter 添加到 Spring Security 默认的 UsernamePasswordAuthenticationFilter 之前执行
            // 这样在尝试基于用户名/密码认证之前，请求会先尝试通过 JWT 完成认证
            .addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);

        // 构建并返回配置好的 SecurityFilterChain
        return http.build();
    }
}