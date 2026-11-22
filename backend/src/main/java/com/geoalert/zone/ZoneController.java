package com.geoalert.zone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Added endpoint (not part of the fixed contract): zones for the console map. Served from the
 * in-memory catalogue, so no data access happens here.
 */
@RestController
public class ZoneController {

    private final ZoneCatalog catalog;
    private final ObjectMapper mapper;

    public ZoneController(ZoneCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @GetMapping("/api/v1/zones")
    public ResponseEntity<Map<String, Object>> zones() throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ZoneCatalog.ZoneRow r : catalog.rows()) {
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("zoneId", r.zoneId());
            z.put("name", r.name());
            z.put("zoneClass", r.zoneClass());
            JsonNode polygons = mapper.readTree(r.geometryJson());
            z.put("polygons", polygons);
            items.add(z);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                .body(Map.of("items", items));
    }
}
