package com.geoalert.alert;

public record AuditEntry(String at, String actor, String action, String fromStatus, String toStatus) {
}
