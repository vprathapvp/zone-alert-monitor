package com.geoalert.ingest;

/** A rejected line as stored for later inspection. raw is capped at {@link #MAX_RAW} bytes. */
public record RejectedLine(int lineNo, RejectionCode code, String detail, byte[] raw, boolean truncated) {

    public static final int MAX_RAW = 8192;

    public static RejectedLine of(BatchLine line, RejectionCode code, String detail) {
        byte[] raw = line.raw();
        boolean truncated = raw.length > MAX_RAW;
        if (truncated) {
            raw = java.util.Arrays.copyOf(raw, MAX_RAW);
        }
        return new RejectedLine(line.lineNo(), code, detail, raw, truncated);
    }
}
