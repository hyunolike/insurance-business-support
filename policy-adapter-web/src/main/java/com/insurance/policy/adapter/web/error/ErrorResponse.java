package com.insurance.policy.adapter.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** 공통 오류 응답. claims와 같은 형태를 쓴다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String traceId,
        Instant timestamp,
        int status,
        String code,
        String message,
        List<FieldError> details
) {

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(currentTraceId(), Instant.now(), status, code, message, null);
    }

    public static ErrorResponse of(int status, String code, String message,
                                   List<FieldError> details) {
        return new ErrorResponse(currentTraceId(), Instant.now(), status, code, message, details);
    }

    private static String currentTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId == null ? "none" : traceId;
    }

    public record FieldError(String field, String reason) {
    }
}
