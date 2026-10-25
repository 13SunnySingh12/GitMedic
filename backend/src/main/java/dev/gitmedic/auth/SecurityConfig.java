package dev.gitmedic.auth;

import dev.gitmedic.common.Problems;
import dev.gitmedic.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtService jwt,
      OAuthHandlers oauth,
      ObjectMapper mapper,
      AppProperties props)
      throws Exception {
    http
        // The HTTP session only carries the OAuth `state` during login (SEC-01); the app session
        // is the stateless GM_SESSION JWT, so the security context is never stored in it.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .securityContext(
            c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
        // SEC-05 replaces token CSRF with same-origin + SameSite=Lax + X-Requested-With header.
        .csrf(c -> c.disable())
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/oauth2/**", "/login/oauth2/**", "/actuator/health/**", "/error")
                    .permitAll()
                    .requestMatchers("/api/v1/webhooks/github", "/api/v1/auth/logout")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            o ->
                o.loginPage(props.frontendOrigin() + "/login")
                    .successHandler(oauth)
                    .failureHandler(oauth))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) ->
                        Problems.write(
                            res,
                            mapper,
                            HttpStatus.UNAUTHORIZED,
                            "UNAUTHENTICATED",
                            "Sign in required.")))
        .addFilterBefore(
            new SessionFilters.JwtCookieFilter(jwt), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new SessionFilters.CsrfHeaderFilter(mapper), SessionFilters.JwtCookieFilter.class)
        .addFilterBefore(
            new SessionFilters.BodySizeFilter(mapper), SessionFilters.CsrfHeaderFilter.class);
    return http.build();
  }
}
