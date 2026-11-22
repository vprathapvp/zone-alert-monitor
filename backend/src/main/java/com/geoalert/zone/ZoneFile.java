package com.geoalert.zone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the upstream zones.geojson. Structural errors (unknown class, duplicate id, broken ring)
 * fail the load: a wrong zone silently raising or suppressing alerts is worse than a failed deploy.
 * Tolerable deviations from RFC 7946 (ring orientation, antimeridian crossing, unclosed ring) are
 * corrected and reported as warnings.
 */
public final class ZoneFile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Parsed(List<Zone> zones, List<String> warnings) {
    }

    private ZoneFile() {
    }

    public static Parsed parse(byte[] json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !"FeatureCollection".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("zone file is not a GeoJSON FeatureCollection");
        }
        List<Zone> zones = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode feature : root.path("features")) {
            JsonNode props = feature.path("properties");
            String id = text(props, "zoneId");
            if (id == null) {
                throw new IllegalArgumentException("zone without zoneId");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate zoneId " + id);
            }
            String name = text(props, "name");
            if (name == null) {
                warnings.add(id + ": no name, using the id");
                name = id;
            }
            String cls = text(props, "class");
            Zone.ZoneClass zoneClass;
            try {
                zoneClass = Zone.ZoneClass.valueOf(cls == null ? "" : cls.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(id + ": unknown class '" + cls + "'");
            }
            zones.add(new Zone(id, name, zoneClass, geometry(id, feature.path("geometry"), warnings)));
        }
        if (zones.isEmpty()) {
            throw new IllegalArgumentException("zone file holds no zones");
        }
        return new Parsed(zones, warnings);
    }

    private static ZoneGeometry geometry(String id, JsonNode geometry, List<String> warnings) {
        String type = geometry.path("type").asText();
        JsonNode coords = geometry.path("coordinates");
        List<JsonNode> polygons = new ArrayList<>();
        switch (type) {
            case "Polygon" -> polygons.add(coords);
            case "MultiPolygon" -> coords.forEach(polygons::add);
            default -> throw new IllegalArgumentException(id + ": unsupported geometry type '" + type + "'");
        }
        List<ZoneGeometry.Polygon> result = new ArrayList<>();
        for (JsonNode polygon : polygons) {
            List<double[][]> rings = new ArrayList<>();
            for (JsonNode ringNode : polygon) {
                rings.add(ring(id, ringNode, warnings));
            }
            if (rings.isEmpty()) {
                throw new IllegalArgumentException(id + ": polygon without rings");
            }
            ZoneGeometry.Polygon p = ZoneGeometry.polygon(rings);
            if (p.maxLon() > 180 || p.minLon() < -180) {
                warnings.add(id + ": crosses the 180th meridian; unwrapped instead of spanning the globe");
            }
            if (ZoneGeometry.signedArea(p.rings().get(0)) < 0) {
                warnings.add(id + ": outer ring is clockwise (RFC 7946 expects counter-clockwise); orientation ignored");
            }
            result.add(p);
        }
        return new ZoneGeometry(result);
    }

    private static double[][] ring(String id, JsonNode ringNode, List<String> warnings) {
        List<double[]> points = new ArrayList<>();
        for (JsonNode pos : ringNode) {
            if (!pos.isArray() || pos.size() < 2 || !pos.get(0).isNumber() || !pos.get(1).isNumber()) {
                throw new IllegalArgumentException(id + ": invalid position " + pos);
            }
            double lon = pos.get(0).asDouble();
            double lat = pos.get(1).asDouble();
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalArgumentException(id + ": position out of range " + pos);
            }
            points.add(new double[]{lon, lat});
        }
        if (points.size() >= 2) {
            double[] first = points.get(0);
            double[] last = points.get(points.size() - 1);
            if (first[0] != last[0] || first[1] != last[1]) {
                warnings.add(id + ": ring not closed; closed it");
                points.add(first.clone());
            }
        }
        if (points.size() < 4) {
            throw new IllegalArgumentException(id + ": ring has fewer than 4 positions");
        }
        return points.toArray(new double[0][]);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isTextual() || v.asText().isBlank() ? null : v.asText();
    }

    /** Normalised geometry as stored in the database and served to the console. */
    public static String toJson(ZoneGeometry geometry) {
        ArrayNode polys = JsonNodeFactory.instance.arrayNode();
        for (ZoneGeometry.Polygon p : geometry.polygons()) {
            ArrayNode rings = polys.addArray();
            for (double[][] ring : p.rings()) {
                ArrayNode r = rings.addArray();
                for (double[] pt : ring) {
                    r.addArray().add(pt[0]).add(pt[1]);
                }
            }
        }
        return polys.toString();
    }

    public static ZoneGeometry fromJson(String json) throws IOException {
        List<ZoneGeometry.Polygon> polygons = new ArrayList<>();
        for (JsonNode poly : MAPPER.readTree(json)) {
            List<double[][]> rings = new ArrayList<>();
            for (JsonNode ringNode : poly) {
                double[][] ring = new double[ringNode.size()][];
                for (int i = 0; i < ringNode.size(); i++) {
                    ring[i] = new double[]{ringNode.get(i).get(0).asDouble(), ringNode.get(i).get(1).asDouble()};
                }
                rings.add(ring);
            }
            polygons.add(ZoneGeometry.polygon(rings));
        }
        return new ZoneGeometry(polygons);
    }
}
