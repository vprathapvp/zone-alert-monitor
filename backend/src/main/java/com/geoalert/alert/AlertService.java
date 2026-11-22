package com.geoalert.alert;

import com.geoalert.common.ApiException;
import com.geoalert.common.ErrorCode;
import com.geoalert.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Alert lifecycle. Each command locks the alert row, decides from its current state and writes in
 * the same transaction. A repeated command that changes nothing writes nothing (and so adds no
 * audit row). The rules of Table 6 of the brief map one to one onto the switch arms below.
 */
@Service
public class AlertService {

    private static final int MAX_NOTE = 500;

    private final AlertRepository repo;

    public AlertService(AlertRepository repo) {
        this.repo = repo;
    }

    public record Page(List<Alert> items, String nextCursor) {
    }

    @Transactional
    public Alert claim(String alertId, CurrentUser user) {
        Alert a = locked(alertId);
        return switch (a.status()) {
            case OPEN -> repo.claim(id(alertId), user.username());
            case CLAIMED -> {
                if (user.username().equals(a.claimedBy())) {
                    yield a;
                }
                throw new ApiException(ErrorCode.ALERT_ALREADY_CLAIMED, "Alert is already claimed by " + a.claimedBy());
            }
            case RESOLVED -> throw new ApiException(ErrorCode.INVALID_STATE, "Alert is resolved");
        };
    }

    @Transactional
    public Alert release(String alertId, CurrentUser user) {
        Alert a = locked(alertId);
        if (a.status() != AlertStatus.CLAIMED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Alert is not claimed");
        }
        requireOwnerOrSupervisor(a, user);
        return repo.release(id(alertId), user.username());
    }

    @Transactional
    public Alert resolve(String alertId, String note, CurrentUser user) {
        if (note == null || note.isBlank() || note.codePointCount(0, note.length()) > MAX_NOTE) {
            throw ApiException.validation("note must be 1 to 500 characters and not blank");
        }
        Alert a = locked(alertId);
        return switch (a.status()) {
            case OPEN -> throw new ApiException(ErrorCode.INVALID_STATE, "Alert is open: claim it first");
            case RESOLVED -> {
                if (user.username().equals(a.resolvedBy())) {
                    yield a;
                }
                throw new ApiException(ErrorCode.INVALID_STATE, "Alert was resolved by " + a.resolvedBy());
            }
            case CLAIMED -> {
                requireOwnerOrSupervisor(a, user);
                yield repo.resolve(id(alertId), user.username(), note);
            }
        };
    }

    @Transactional(readOnly = true)
    public Alert get(String alertId) {
        return repo.find(id(alertId)).orElseThrow(() -> ApiException.notFound("Alert not found"));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> audit(String alertId) {
        long id = id(alertId);
        if (repo.find(id).isEmpty()) {
            throw ApiException.notFound("Alert not found");
        }
        return repo.audit(id);
    }

    @Transactional(readOnly = true)
    public Page list(AlertStatus status, String assetId, String zoneId, String cursor, int limit) {
        long after = cursor == null ? 0 : decodeCursor(cursor);
        List<Alert> rows = repo.page(status, assetId, zoneId, after, limit + 1);
        if (rows.size() <= limit) {
            return new Page(rows, null);
        }
        List<Alert> items = rows.subList(0, limit);
        return new Page(items, encodeCursor(Long.parseLong(items.get(limit - 1).alertId())));
    }

    private Alert locked(String alertId) {
        return repo.lock(id(alertId)).orElseThrow(() -> ApiException.notFound("Alert not found"));
    }

    private static void requireOwnerOrSupervisor(Alert a, CurrentUser user) {
        if (!user.isSupervisor() && !user.username().equals(a.claimedBy())) {
            throw new ApiException(ErrorCode.NOT_ALERT_OWNER, "Only the owner or a supervisor may do this");
        }
    }

    private static long id(String alertId) {
        try {
            long id = Long.parseLong(alertId);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw ApiException.notFound("Alert not found");
    }

    static String encodeCursor(long lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("a:" + lastId).getBytes(StandardCharsets.UTF_8));
    }

    static long decodeCursor(String cursor) {
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (s.startsWith("a:")) {
                long id = Long.parseLong(s.substring(2));
                if (id >= 0) {
                    return id;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // falls through
        }
        throw ApiException.validation("cursor is not valid");
    }
}
