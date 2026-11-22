package com.geoalert.ingest;

/** One non-empty line of a batch. lineNo is 1-based among the non-empty lines. */
public record BatchLine(int lineNo, String text, byte[] raw) {
}
