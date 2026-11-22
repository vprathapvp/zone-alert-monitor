package com.geoalert.alert;

import com.geoalert.common.Times;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Alert as returned by the API. version is an addition to the contract shape: it grows with every
 * change so the console can tell which of two copies (REST snapshot vs live event) is newer.
 */
public record Alert(
        String alertId,
        String assetId,
        String zoneId,
        AlertStatus status,
        String openedAt,
        String triggerReportId,
        String triggerObservedAt,
        String claimedBy,
        String claimedAt,
        String resolvedBy,
        String resolvedAt,
        String resolutionNote,
        int version) {

    public static final String COLUMNS = """
            alert_id, asset_id, zone_id, status, opened_at, trigger_report_id, trigger_observed_at,
            claimed_by, claimed_at, resolved_by, resolved_at, resolution_note, version""";

    public static Alert map(ResultSet rs, int rowNum) throws SQLException {
        return new Alert(
                String.valueOf(rs.getLong(1)),
                rs.getString(2),
                rs.getString(3),
                AlertStatus.valueOf(rs.getString(4)),
                Times.format(rs.getTimestamp(5)),
                rs.getString(6),
                Times.format(rs.getTimestamp(7)),
                rs.getString(8),
                Times.format(rs.getTimestamp(9)),
                rs.getString(10),
                Times.format(rs.getTimestamp(11)),
                rs.getString(12),
                rs.getInt(13));
    }
}
