package br.com.vovolinux.m2m.config;

import br.com.vovolinux.m2m.security.M2mUserDetails;
import br.com.vovolinux.m2m.security.SecondFactorFilter;
import br.com.vovolinux.m2m.security.TotpService;
import br.com.vovolinux.m2m.web.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Rotas do instalador ({@code /internal/instalador/**}): sem sessão nem CSRF; o próprio controlador exige a origem
     * loopback do container e o token derivado da chave mestra (InstallerController).
     */
    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/instalador/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json, TotpService totp) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // token carregado a cada requisição (cookie para o SPA)
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/auth/csrf").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((req, res, auth) -> {
                            if (auth.getPrincipal() instanceof M2mUserDetails u && totp.isEnabled(u.id())) {
                                // segundo fator ativo: a sessão fica pendente e só aceita o código (SecondFactorFilter)
                                req.getSession().setAttribute(SecondFactorFilter.PENDING, new SecondFactorFilter.Pending(
                                        u.id(), Instant.now().plusSeconds(SecondFactorFilter.VALID_SECONDS)));
                                res.setStatus(HttpStatus.ACCEPTED.value());
                                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                json.writeValue(res.getOutputStream(), Map.of("segundoFator", true));
                            } else {
                                res.setStatus(HttpStatus.NO_CONTENT.value());
                            }
                        })
                        .failureHandler((req, res, e) -> falhaDeLogin(json, res, e)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.NO_CONTENT.value())))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new SecondFactorFilter(json), AuthorizationFilter.class)
                .sessionManagement(session -> session.sessionFixation(fix -> fix.changeSessionId()));
        return http.build();
    }

    private static void falhaDeLogin(JsonMapper json, HttpServletResponse res, AuthenticationException e)
            throws IOException {
        boolean bloqueado = e instanceof LockedException;
        res.setStatus(bloqueado ? HttpStatus.LOCKED.value() : HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> params = bloqueado ? Map.of("minutos", 5) : Map.of();
        json.writeValue(res.getOutputStream(),
                new ApiError(bloqueado ? "auth.conta-bloqueada" : "auth.credenciais-invalidas", params));
    }
}
