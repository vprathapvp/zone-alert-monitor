package com.geoalert.live;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Added endpoint: live stream for the console. Events: hello, positions (array of AssetPosition),
 * alerts (array of Alert), resync (reload the snapshot). Requires a bearer token like the REST API.
 */
@RestController
public class LiveController {

    private final LiveHub hub;

    public LiveController(LiveHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/api/v1/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe();
    }
}
