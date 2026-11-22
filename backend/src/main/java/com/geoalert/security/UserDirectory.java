package com.geoalert.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geoalert.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Users from the deploy-time users file (mounted, never baked into the image). Passwords are
 * hashed with BCrypt on load and the plain text is dropped; nothing from the file is logged
 * except the count of users per role.
 */
@Component
public class UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);

    public record User(String username, Role role, String passwordHash) {
    }

    private final Map<String, User> users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    /** Compared against when the username is unknown, so both paths cost the same time. */
    private final String dummyHash = encoder.encode("timing-equaliser");

    public UserDirectory(AppProperties props, ObjectMapper mapper) throws IOException {
        Path path = Path.of(props.usersFile());
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("users file is not readable at the configured USERS_FILE path");
        }
        JsonNode root = mapper.readTree(path.toFile());
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("users file must be a JSON array");
        }
        Map<String, User> loaded = new HashMap<>();
        Map<Role, Integer> perRole = new HashMap<>();
        for (JsonNode u : root) {
            String username = u.path("username").asText("");
            String password = u.path("password").asText("");
            Role role;
            try {
                role = Role.valueOf(u.path("role").asText(""));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("users file: entry #" + loaded.size() + " has an unknown role");
            }
            if (username.isBlank() || password.isEmpty()) {
                throw new IllegalStateException("users file: entry #" + loaded.size() + " lacks username or password");
            }
            if (loaded.put(username, new User(username, role, encoder.encode(password))) != null) {
                throw new IllegalStateException("users file: duplicate username");
            }
            perRole.merge(role, 1, Integer::sum);
        }
        this.users = Map.copyOf(loaded);
        log.info("users loaded: {}", perRole);
    }

    public Optional<User> authenticate(String username, String password) {
        User user = username == null ? null : users.get(username);
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean ok = password != null && encoder.matches(password, hash);
        return ok && user != null ? Optional.of(user) : Optional.empty();
    }
}
