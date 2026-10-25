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
    private final Predicate<String> userExists;

    public JwtAuthFilter(JwtService jwt) {
        this(jwt, userId -> true);
    }

    /**
     * @param userExists checked after the signature: a JWT stays valid until it expires, so
     *                   without this a deleted account (DELETE /api/me) could keep using its token
     *                   and recreate data for a user that no longer exists.
     */
    public JwtAuthFilter(JwtService jwt, Predicate<String> userExists) {
        this.jwt = jwt;
        this.userExists = userExists;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                String userId = jwt.verify(header.substring(7));
                if (!userExists.test(userId)) throw new IllegalStateException("account deleted");
                var auth = new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
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
