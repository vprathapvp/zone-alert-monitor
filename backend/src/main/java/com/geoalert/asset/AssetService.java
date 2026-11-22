package com.geoalert.asset;

import com.geoalert.common.ApiException;
import com.geoalert.common.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssetService {

    private final JdbcTemplate jdbc;

    public AssetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Page<T>(List<T> items, boolean truncated) {
    }

    /**
     * Current positions inside the box, ordered by assetId. Uses the GiST index on point(lon, lat);
     * a box that crosses the 180th meridian is queried as two boxes.
     */
    @Transactional(readOnly = true)
    public Page<AssetPosition> inBox(BBox box, int limit) {
        String where;
        List<Object> args = new ArrayList<>();
        if (box.crossesAntimeridian()) {
            where = "(point(lon, lat) <@ box(point(?, ?), point(180, ?)) OR point(lon, lat) <@ box(point(-180, ?), point(?, ?)))";
            args.addAll(List.of(box.minLon(), box.minLat(), box.maxLat(), box.minLat(), box.maxLon(), box.maxLat()));
        } else {
            where = "point(lon, lat) <@ box(point(?, ?), point(?, ?))";
            args.addAll(List.of(box.minLon(), box.minLat(), box.maxLon(), box.maxLat()));
        }
        args.add(limit + 1);
        List<AssetPosition> rows = jdbc.query(
                "SELECT " + AssetPosition.COLUMNS + " FROM asset_position WHERE " + where + " ORDER BY asset_id LIMIT ?",
                AssetPosition::map, args.toArray());
        return truncate(rows, limit);
    }

    @Transactional(readOnly = true)
    public AssetPosition get(String assetId) {
        return jdbc.query("SELECT " + AssetPosition.COLUMNS + " FROM asset_position WHERE asset_id = ?",
                        AssetPosition::map, assetId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Asset not found"));
    }

    /** Stored reports of one asset, oldest observedAt first, bounded by limit. */
    @Transactional(readOnly = true)
    public Page<TrackPoint> track(String assetId, Instant from, Instant to, int limit) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_position WHERE asset_id = ?)",
                Boolean.class, assetId);
        if (!Boolean.TRUE.equals(exists)) {
            throw ApiException.notFound("Asset not found");
        }
        List<TrackPoint> rows = jdbc.query("""
                SELECT report_id, observed_at, lat, lon, source
                  FROM report
                 WHERE asset_id = ?
                   AND (?::timestamptz IS NULL OR observed_at >= ?::timestamptz)
                   AND (?::timestamptz IS NULL OR observed_at <= ?::timestamptz)
                 ORDER BY observed_at, report_id
                 LIMIT ?
                """, (rs, i) -> new TrackPoint(rs.getString(1), Times.format(rs.getTimestamp(2)),
                        rs.getDouble(3), rs.getDouble(4), rs.getString(5)),
                assetId, ts(from), ts(from), ts(to), ts(to), limit + 1);
        return truncate(rows, limit);
    }

    private static java.sql.Timestamp ts(Instant i) {
        return i == null ? null : java.sql.Timestamp.from(i);
    }

    private static <T> Page<T> truncate(List<T> rows, int limit) {
        boolean truncated = rows.size() > limit;
        return new Page<>(truncated ? rows.subList(0, limit) : rows, truncated);
    }
}
