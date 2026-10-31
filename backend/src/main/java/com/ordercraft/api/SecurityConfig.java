package com.ordercraft.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;

@Configuration @EnableWebSecurity
public class SecurityConfig {
  @Bean SecurityFilterChain security(HttpSecurity http,JdbcTemplate db,Environment env) throws Exception {
    String secret=env.getProperty("ordercraft.jwt-secret");
    http.csrf(c->c.disable()).cors(c->c.configurationSource(req->{var cfg=new CorsConfiguration();cfg.setAllowedOriginPatterns(List.of("*"));cfg.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));cfg.setAllowedHeaders(List.of("Authorization","Content-Type","X-Company-Id"));return cfg;}))
      .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/**","/actuator/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll().requestMatchers(HttpMethod.OPTIONS,"/**").permitAll().anyRequest().authenticated())
      .addFilterBefore(new TokenFilter(db,secret),UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
