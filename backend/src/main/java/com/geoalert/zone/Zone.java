package com.geoalert.zone;

public record Zone(String zoneId, String name, ZoneClass zoneClass, ZoneGeometry geometry) {

    public enum ZoneClass { RESTRICTED, ADVISORY }

    public boolean raisesAlerts() {
        return zoneClass == ZoneClass.RESTRICTED;
    }
}
