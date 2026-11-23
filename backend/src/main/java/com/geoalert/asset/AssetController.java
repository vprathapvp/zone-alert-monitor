package com.geoalert.asset;

import com.geoalert.common.ApiException;
import com.geoalert.common.Params;
import com.geoalert.ingest.SourceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class AssetController {

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/api/v1/assets")
    public AssetService.Page<AssetPosition> inBox(@RequestParam(required = false) String bbox,
                                                  @RequestParam(required = false) String limit) {
        return assets.inBox(BBox.parse(bbox), Params.limit(limit, 1000, 5000));
    }

    @GetMapping("/api/v1/assets/{assetId}")
    public AssetPosition get(@PathVariable String assetId) {
        return assets.get(assetId);
    }

    @GetMapping("/api/v1/assets/{assetId}/track")
    public AssetService.Page<TrackPoint> track(@PathVariable String assetId,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String limit,
                                               @RequestParam(required = false) String sourceType) {
        Instant f = Params.instant("from", from);
        Instant t = Params.instant("to", to);
        if (f != null && t != null && f.isAfter(t)) {
            throw ApiException.validation("from must not be after to");
        }
        SourceType type = null;
        if (sourceType != null) {
            try {
                type = SourceType.valueOf(sourceType);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("sourceType must be SATELLITE, GROUND, MOBILE or UNKNOWN");
            }
        }
        return assets.track(assetId, f, t, type, Params.limit(limit, 500, 5000));
    }
}
