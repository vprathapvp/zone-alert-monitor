package com.geoalert.asset;

import com.geoalert.common.Times;

import java.sql.ResultSet;
import java.sql.SQLException;

public record AssetPosition(
        String assetId,
        String kind,
        String reportId,
        String observedAt,
        double lat,
        double lon,
        Double speedKn,
        Double headingDeg,
        String source) {

    /** Column order: asset_id, kind, report_id, observed_at, lat, lon, speed_kn, heading_deg, source. */
    public static final String COLUMNS = "asset_id, kind, report_id, observed_at, lat, lon, speed_kn, heading_deg, source";

    public static AssetPosition map(ResultSet rs, int rowNum) throws SQLException {
        return new AssetPosition(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                Times.format(rs.getTimestamp(4)),
                rs.getDouble(5),
                rs.getDouble(6),
                rs.getObject(7, Double.class),
                rs.getObject(8, Double.class),
                rs.getString(9));
    }
}
