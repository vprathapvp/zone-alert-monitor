package com.geoalert.security;

import com.geoalert.AppProperties;
import com.geoalert.common.ApiException;
import com.geoalert.common.ErrorCode;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
public class AuthService {

    private final UserDirectory users;
    private final JwtEncoder encoder;
    private final AppProperties props;
    private final Clock clock;

    public AuthService(UserDirectory users, JwtEncoder encoder, AppProperties props, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public record LoginResult(String accessToken, String tokenType, long expiresInSec, Map<String, String> user) {
    }

    public LoginResult login(String username, String password) {
        UserDirectory.User user = users.authenticate(username, password)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Unknown username or wrong password"));
        Instant now = clock.instant();
        long ttl = props.tokenTtl().toSeconds();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.username())
                .claim("role", user.role().name())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttl))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new LoginResult(token, "Bearer", ttl, Map.of("username", user.username(), "role", user.role().name()));
    }
}
