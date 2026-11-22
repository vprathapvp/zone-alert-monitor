package com.geoalert.ingest;

import java.util.List;

public record BatchResult(int received, int accepted, int duplicates, int rejected, List<Rejection> rejections) {

    public record Rejection(int line, String code, String detail) {
    }
}
