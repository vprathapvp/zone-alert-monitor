package com.geoalert.common;

/** A refusal that maps one to one onto an RFC 9457 problem response. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail, null, false, false);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
