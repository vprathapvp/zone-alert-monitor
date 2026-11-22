package com.geoalert.ingest;

import com.geoalert.common.ApiException;
import com.geoalert.common.ErrorCode;
import com.geoalert.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
public class IngestController {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_LINES = 1000;
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final IngestService ingest;

    public IngestController(IngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/api/v1/reports/batch")
    public BatchResult batch(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        MediaType type;
        try {
            type = contentType == null ? null : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null || !NDJSON.equalsTypeAndSubtype(type)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/x-ndjson");
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "A batch is at most 5 MB");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BYTES + 1);
        }
        if (body.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "A batch is at most 5 MB");
        }
        List<BatchLine> lines = split(body);
        if (lines.size() > MAX_LINES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "A batch is at most 1,000 non-empty lines");
        }
        return ingest.ingest(lines, (String) request.getAttribute(RequestIdFilter.MDC_KEY));
    }

    /**
     * Splits on LF (a trailing CR is dropped). Lines that are empty or whitespace only are skipped
     * and not numbered. Invalid UTF-8 is replaced, so such a line fails validation instead of the
     * request failing; the raw bytes are kept for the rejection record.
     */
    static List<BatchLine> split(byte[] body) {
        List<BatchLine> lines = new ArrayList<>();
        int start = 0;
        int lineNo = 0;
        for (int i = 0; i <= body.length; i++) {
            if (i == body.length || body[i] == '\n') {
                int end = i;
                if (end > start && body[end - 1] == '\r') {
                    end--;
                }
                if (end > start) {
                    byte[] raw = Arrays.copyOfRange(body, start, end);
                    String text = new String(raw, StandardCharsets.UTF_8);
                    if (!text.isBlank()) {
                        lines.add(new BatchLine(++lineNo, text, raw));
                    }
                }
                start = i + 1;
            }
        }
        return lines;
    }
}
