package group12.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures HTTP security for the backend API.
 *
 * <p>The API is stateless and accepts bearer JWTs validated by the OAuth2 resource-server
 * support. Only the database ping and API documentation endpoints are public; all other
 * requests require an authenticated token. A {@code roles} JWT claim is mapped to Spring
 * {@code ROLE_*} authorities for route rules and method security.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Uses the same BCrypt work factor as the NestJS auth service for newly
     * encoded passwords. BCrypt hashes include their salt and work factor.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Builds the application's security filter chain.
     *
     * @param http Spring's HTTP security builder
     * @return the configured stateless JWT resource-server filter chain
     * @throws Exception if the filter chain cannot be configured
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Requests use bearer tokens rather than cookie-backed sessions, so CSRF is disabled.
                .csrf(csrf -> csrf.disable())
                // Do not create or use an HTTP session; authenticate each request from its JWT.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Keep operational health checks and OpenAPI documentation publicly accessible.
                        .requestMatchers("/db/ping", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                // Validate Authorization: Bearer tokens using the JwtDecoder configured in JwtConfiguration.
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Maps each string in the signed JWT {@code roles} claim to a Spring role authority.
     * For example, {@code ["ADMIN", "FINANCE"]} becomes {@code ROLE_ADMIN} and
     * {@code ROLE_FINANCE}, which can be checked with {@code hasRole("ADMIN")}.
     * Tokens without a roles claim remain authenticated but have no role authorities.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return authenticationConverter;
    }
}
