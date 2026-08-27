package com.pkmprojects.mongodbserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Form-login security: every page requires authentication, database provisioning
 * (create/update/delete) additionally requires the ADMIN role.
 *
 * <p>CSRF protection stays enabled (default) - all forms carry tokens via Thymeleaf
 * {@code th:action}. Write protection is enforced twice: route matchers here and
 * {@code @PreAuthorize} on the controllers.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Security filter chain: form login, CSRF enabled, static/login pages public,
     * all database write routes restricted to the ADMIN role.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/webjars/**", "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/adminer/**", "/mongo-express/**").hasRole("ADMIN")
                        .requestMatchers("/databases/*/reset", "/databases/*/delete",
                                "/databases/*/backup", "/databases/*/restore",
                                "/databases/*/collections/*/import").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/databases", "/databases/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/mongo/databases/**", "/postgres/databases/**").hasRole("ADMIN")
                        .requestMatchers("/webhooks", "/webhooks/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // The proxied UIs have their own CSRF protection; Spring's token would otherwise reject their POSTs.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/mongo-express/**", "/adminer/**"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll());
        return http.build();
    }

    /**
     * BCrypt encoder used for the in-memory admin user.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Single admin principal derived from {@code APP_ADMIN_USERNAME} /
     * {@code APP_ADMIN_PASSWORD} with the ADMIN role.
     */
    @Bean
    UserDetailsService userDetailsService(AdminProperties adminProperties, PasswordEncoder passwordEncoder) {
        var admin = User.withUsername(adminProperties.username())
                .password(passwordEncoder.encode(adminProperties.password()))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
