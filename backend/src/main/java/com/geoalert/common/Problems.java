package com.geoalert.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds application/problem+json bodies. Details are written for clients: no internals. */
public final class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private Problems() {
    }

    public static Map<String, Object> body(ErrorCode code, int status, String detail, String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase());
        body.put("status", status);
        body.put("code", code.name());
        if (detail != null) {
            body.put("detail", detail);
        }
        if (instance != null) {
            body.put("instance", instance);
        }
        return body;
    }

    public static ResponseEntity<Object> entity(ErrorCode code, int status, String detail, String instance) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(PROBLEM_JSON);
        return new ResponseEntity<>(body(code, status, detail, instance), headers, status);
    }

    /** For filters (security) that answer before the MVC layer. */
    public static void write(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res,
                             ErrorCode code, String detail) throws IOException {
        int status = code.status().value();
        res.setStatus(status);
        res.setContentType(PROBLEM_JSON.toString());
        mapper.writeValue(res.getOutputStream(), body(code, status, detail, req.getRequestURI()));
    }
}
