package dev.hellowrc.circlechat.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 未登录也允许访问
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/users/register",
                                "/api/v1/auth/csrf",
                                "/swagger-ui.html",
                                // swagger
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()

                        // 举例：admin API
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")

                        // WebSocket 握手要求登录
                        .requestMatchers("/ws/**")
                        .authenticated()

                        // 剩下 API 全部要求登录
                        .anyRequest()
                        .authenticated()
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
