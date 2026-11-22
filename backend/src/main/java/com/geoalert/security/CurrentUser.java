package com.geoalert.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public record CurrentUser(String username, Role role) {

    public boolean isSupervisor() {
        return role == Role.SUPERVISOR;
    }

    public static CurrentUser of(Authentication auth) {
        Role role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> Role.valueOf(a.substring(5)))
                .findFirst()
                .orElseThrow();
        return new CurrentUser(auth.getName(), role);
    }
}
