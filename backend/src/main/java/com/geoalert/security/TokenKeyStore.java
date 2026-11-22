package com.geoalert.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * HMAC key for bearer tokens. Both instances must verify each other's tokens, so the key lives
 * in the database: the first instance to start creates it, the other reads the same row.
 * This keeps the operator from having to generate and distribute a secret on a machine that has
 * only docker, tar, gzip, cat and sha256sum.
 */
@Component
public class TokenKeyStore {

    private final byte[] key;

    public TokenKeyStore(JdbcTemplate jdbc) {
        byte[] candidate = new byte[32];
        new SecureRandom().nextBytes(candidate);
        jdbc.update("INSERT INTO app_secret (name, value) VALUES ('token-hmac-key', ?) ON CONFLICT (name) DO NOTHING",
                (Object) candidate);
        this.key = jdbc.queryForObject("SELECT value FROM app_secret WHERE name = 'token-hmac-key'", byte[].class);
    }

    public byte[] key() {
        return key.clone();
    }
}
