package com.datagent.agentv2.security;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import com.datagent.agentv2.auth.application.AuthenticationService;
import com.datagent.agentv2.auth.config.AuthProperties;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class WebSecurityConfiguration {

    @Bean
    SecurityFilterChain authSecurityFilterChain(
            HttpSecurity http,
            AuthenticationService authentication,
            AuthProperties properties,
            ObjectMapper objectMapper)
            throws Exception {
        var filter =
                new LoginSessionAuthenticationFilter(
                        authentication, properties.getSession().getCookieName());
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers(
                                                "/api/v1/auth/login", "/actuator/health/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        exceptions ->
                                exceptions.authenticationEntryPoint(
                                        (request, response, failure) -> {
                                            response.setStatus(401);
                                            response.setContentType(
                                                    MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                                            writeProblem(objectMapper, request, response);
                                        }))
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void writeProblem(
            ObjectMapper objectMapper,
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response)
            throws IOException {
        objectMapper.writeValue(
                response.getOutputStream(),
                AuthenticationProblemDetails.required(request, response));
    }
}
