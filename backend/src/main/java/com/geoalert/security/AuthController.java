package com.geoalert.security;

import com.geoalert.common.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/api/v1/auth/login")
    public AuthService.LoginResult login(@RequestBody LoginRequest body) {
        if (body == null || body.username() == null || body.password() == null) {
            throw ApiException.validation("username and password are required");
        }
        return auth.login(body.username(), body.password());
    }
}
