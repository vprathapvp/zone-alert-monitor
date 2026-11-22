package com.geoalert.alert;

import com.geoalert.common.ApiException;
import com.geoalert.common.Params;
import com.geoalert.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    public record ResolveRequest(String note) {
    }

    @GetMapping("/api/v1/alerts")
    public AlertService.Page list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String assetId,
                                  @RequestParam(required = false) String zoneId,
                                  @RequestParam(required = false) String limit,
                                  @RequestParam(required = false) String cursor) {
        AlertStatus s = null;
        if (status != null) {
            try {
                s = AlertStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("status must be OPEN, CLAIMED or RESOLVED");
            }
        }
        return alerts.list(s, assetId, zoneId, cursor, Params.limit(limit, 100, 500));
    }

    @GetMapping("/api/v1/alerts/{alertId}")
    public Alert get(@PathVariable String alertId) {
        return alerts.get(alertId);
    }

    @GetMapping("/api/v1/alerts/{alertId}/audit")
    public Map<String, List<AuditEntry>> audit(@PathVariable String alertId) {
        return Map.of("items", alerts.audit(alertId));
    }

    @PostMapping("/api/v1/alerts/{alertId}/claim")
    public Alert claim(@PathVariable String alertId, Authentication auth) {
        return alerts.claim(alertId, CurrentUser.of(auth));
    }

    @PostMapping("/api/v1/alerts/{alertId}/release")
    public Alert release(@PathVariable String alertId, Authentication auth) {
        return alerts.release(alertId, CurrentUser.of(auth));
    }

    @PostMapping("/api/v1/alerts/{alertId}/resolve")
    public Alert resolve(@PathVariable String alertId, @RequestBody(required = false) ResolveRequest body,
                         Authentication auth) {
        return alerts.resolve(alertId, body == null ? null : body.note(), CurrentUser.of(auth));
    }
}
