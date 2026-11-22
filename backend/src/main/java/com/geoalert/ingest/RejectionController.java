package com.geoalert.ingest;

import com.geoalert.common.ApiException;
import com.geoalert.common.Params;
import com.geoalert.common.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Added endpoint (SUPERVISOR): rejected lines for inspection (R6): what the line was, why it was
 * rejected and when. Newest first, cursor paged.
 */
@RestController
public class RejectionController {

    private final RejectionQuery query;

    public RejectionController(RejectionQuery query) {
        this.query = query;
    }

    @GetMapping("/api/v1/rejections")
    public Map<String, Object> list(@RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String cursor) {
        int n = Params.limit(limit, 100, 500);
        Long before = null;
        if (cursor != null) {
            try {
                before = Long.parseLong(cursor);
            } catch (NumberFormatException e) {
                throw ApiException.validation("cursor is not valid");
            }
        }
        return query.page(n, before);
    }

    @Service
    public static class RejectionQuery {

        private final JdbcTemplate jdbc;

        public RejectionQuery(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional(readOnly = true)
        public Map<String, Object> page(int limit, Long before) {
            List<Map<String, Object>> rows = jdbc.query("""
                    SELECT rejected_id, received_at, request_id, line_no, code, detail, raw, raw_truncated
                      FROM rejected_line
                     WHERE (?::bigint IS NULL OR rejected_id < ?::bigint)
                     ORDER BY rejected_id DESC
                     LIMIT ?
                    """, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong(1));
                m.put("receivedAt", Times.format(rs.getTimestamp(2)));
                m.put("requestId", rs.getString(3));
                m.put("line", rs.getInt(4));
                m.put("code", rs.getString(5));
                m.put("detail", rs.getString(6));
                m.put("raw", new String(rs.getBytes(7), StandardCharsets.UTF_8));
                m.put("rawTruncated", rs.getBoolean(8));
                return m;
            }, before, before, limit + 1);
            String next = null;
            if (rows.size() > limit) {
                rows = rows.subList(0, limit);
                next = String.valueOf(rows.get(limit - 1).get("id"));
            }
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("items", rows);
            page.put("nextCursor", next);
            return page;
        }
    }
}
