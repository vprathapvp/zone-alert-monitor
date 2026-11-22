package com.geoalert.zone;

import java.util.ArrayList;
import java.util.List;

/**
 * A zone's area: one or more polygons, each an outer ring plus holes. Longitudes in each ring are
 * "unwrapped" (consecutive vertices never jump by more than 180 degrees), so a ring that crosses
 * the 180th meridian is stored with longitudes beyond 180 instead of wrapping around the globe.
 * A point is tested at lon, lon+360 and lon-360 so it is found on either side of the meridian.
 */
public final class ZoneGeometry {

    /** ring[i] = {lon, lat}. rings.get(0) is the outer boundary, the rest are holes. */
    public record Polygon(List<double[][]> rings, double minLon, double maxLon, double minLat, double maxLat) {
    }

    private final List<Polygon> polygons;

    public ZoneGeometry(List<Polygon> polygons) {
        this.polygons = List.copyOf(polygons);
    }

    public List<Polygon> polygons() {
        return polygons;
    }

    public boolean contains(double lat, double lon) {
        for (Polygon p : polygons) {
            if (lat < p.minLat() || lat > p.maxLat()) {
                continue;
            }
            for (double candidate : new double[]{lon, lon + 360, lon - 360}) {
                if (candidate < p.minLon() || candidate > p.maxLon()) {
                    continue;
                }
                if (inPolygon(p, candidate, lat)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inPolygon(Polygon p, double x, double y) {
        if (!inRing(p.rings().get(0), x, y)) {
            return false;
        }
        for (int i = 1; i < p.rings().size(); i++) {
            if (inRing(p.rings().get(i), x, y)) {
                return false;
            }
        }
        return true;
    }

    /** Even-odd ray casting. Ring orientation does not matter. */
    static boolean inRing(double[][] ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], yi = ring[i][1];
            double xj = ring[j][0], yj = ring[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Builds a polygon from raw rings, unwrapping longitudes and computing the bounding box. */
    public static Polygon polygon(List<double[][]> rawRings) {
        List<double[][]> rings = new ArrayList<>();
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        Double anchor = null;
        for (double[][] raw : rawRings) {
            double[][] ring = new double[raw.length][];
            for (int i = 0; i < raw.length; i++) {
                double lon = raw[i][0];
                double ref = i == 0 ? (anchor == null ? lon : anchor) : ring[i - 1][0];
                while (lon - ref > 180) {
                    lon -= 360;
                }
                while (lon - ref < -180) {
                    lon += 360;
                }
                ring[i] = new double[]{lon, raw[i][1]};
                minLon = Math.min(minLon, lon);
                maxLon = Math.max(maxLon, lon);
                minLat = Math.min(minLat, raw[i][1]);
                maxLat = Math.max(maxLat, raw[i][1]);
            }
            if (anchor == null) {
                anchor = ring[0][0];
            }
            rings.add(ring);
        }
        return new Polygon(rings, minLon, maxLon, minLat, maxLat);
    }

    /** Signed area (shoelace); positive means counter-clockwise. */
    static double signedArea(double[][] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length - 1; i++) {
            sum += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1];
        }
        return sum / 2;
    }
}
