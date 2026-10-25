package com.ekraah.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

/**
 * Authenticates /api/** calls from the Bearer token. The principal is the userId
 * string — controllers must take the userId from here, never from a request body.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final Predicate<JwtService.AccessToken> sessionActive;

    public JwtAuthFilter(JwtService jwt) {
        this(jwt, token -> true);
    }

    /**
     * @param sessionActive checked after the signature, on every request: the token's server-side
     *                      session must still be active, so logout, password reset and account
     *                      deletion (which revoke or delete sessions) take effect immediately.
     */
    public JwtAuthFilter(JwtService jwt, Predicate<JwtService.AccessToken> sessionActive) {
        this.jwt = jwt;
        this.sessionActive = sessionActive;
    }

    /** Extension tokens can only send orders; a stolen one can't read or delete the account. */
    static boolean allowedForExtension(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/api/orders/import".equals(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.startsWith("/api/auth/") || uri.startsWith("/api/inbound/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.AccessToken token = jwt.verifyAccess(header.substring(7));
                if (!sessionActive.test(token)) throw new IllegalStateException("session revoked or expired");
                if (JwtService.SCOPE_EXTENSION.equals(token.scope()) && !allowedForExtension(request)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"The extension's login can only send orders.\"}");
                    return;
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        token.userId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"Session expired, please log in again.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
