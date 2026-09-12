package com.insurance.policy.adapter.web.error;

import com.insurance.policy.domain.policy.exception.IllegalPolicyTransitionException;
import com.insurance.policy.domain.policy.exception.PolicyNotFoundException;
import com.insurance.policy.domain.policy.exception.SnapshotNotAvailableException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 오류 매핑.
 *
 * <p><b>500은 "우리가 예상하지 못한 것"에만 쓴다.</b> 도메인 규칙 위반은 4xx다.
 * v1은 상태 전이 위반이 500으로 나가서, 클라이언트가 자기 요청이 잘못된 건지
 * 서버가 고장난 건지 구분할 수 없었다.
 *
 * @see docs/design/05-api.md §1.5
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 계약 없음 — 피보험자 불일치도 여기로 온다 (존재 여부를 노출하지 않기 위해) */
    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PolicyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "POLICY_NOT_FOUND", ex.getMessage()));
    }

    /** 그 시점에는 이 계약이 존재하지 않았다 */
    @ExceptionHandler(SnapshotNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleSnapshotUnavailable(
            SnapshotNotAvailableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "SNAPSHOT_NOT_AVAILABLE", ex.getMessage()));
    }

    /** ★ 상태 전이 위반 → 409 (500이 아니다) */
    @ExceptionHandler(IllegalPolicyTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(
            IllegalPolicyTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "POLICY_ILLEGAL_TRANSITION", ex.getMessage()));
    }

    /**
     * ★ 인가 거부 → 403 (500이 아니다).
     *
     * <p>{@code @PreAuthorize}가 거부하면 {@link AuthorizationDeniedException}이
     * 핸들러 메서드 밖으로 던져져 이 어드바이스까지 온다. 아래 catch-all이 먼저
     * 잡으면 <b>모든 권한 거부가 500으로 나간다</b> — 클라이언트는 자기 권한이 없는 건지
     * 서버가 고장난 건지 알 수 없고, 오류 대시보드도 못 쓰게 된다.
     *
     * <p>인증 자체가 없는 경우는 여기까지 오지 않는다. 필터 체인이 먼저 401로 끊는다.
     *
     * <p>사유를 응답에 담지 않는다. "어떤 권한이 없어서 막혔는지"는 권한 구조를 알려주는
     * 정보다. 진단은 로그로 한다.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AuthorizationDeniedException ex) {
        log.warn("인가 거부: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(403, "ACCESS_DENIED", "권한이 없습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "VALIDATION_FAILED",
                        "요청 값이 올바르지 않습니다.", details));
    }

    /**
     * 메서드 파라미터 검증 실패 → 400.
     *
     * <p>Spring 6.1부터 {@code @PathVariable}·{@code @RequestParam}에 붙은 제약과
     * 중첩 {@code @Valid} 위반이 {@link MethodArgumentNotValidException}이 아니라
     * 이 예외로 온다. 둘 다 다루지 않으면 검증 실패가 500으로 나간다.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(
            HandlerMethodValidationException ex) {
        List<ErrorResponse.FieldError> details = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(
                                fieldNameOf(result, error), error.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "VALIDATION_FAILED",
                        "요청 값이 올바르지 않습니다.", details));
    }

    /** 필드 위반이면 그 필드명을, 아니면 파라미터명을 쓴다. */
    private String fieldNameOf(ParameterValidationResult result, MessageSourceResolvable error) {
        if (error instanceof FieldError fieldError) {
            return fieldError.getField();
        }
        String parameterName = result.getMethodParameter().getParameterName();
        return parameterName == null ? "request" : parameterName;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "VALIDATION_FAILED", ex.getMessage()));
    }

    /**
     * 도메인 VO·불변식 위반.
     *
     * <p>{@code IllegalArgumentException}은 대부분 클라이언트 입력이 잘못된 경우다
     * (형식이 틀린 계약번호, 미래의 asOf 등). 400으로 내린다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "INVALID_REQUEST", ex.getMessage()));
    }

    /** 도메인 상태 위반 (이미 정정된 기록을 다시 정정 등) */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "INVALID_STATE", ex.getMessage()));
    }

    /**
     * 예상하지 못한 오류.
     *
     * <p>스택트레이스는 로그로만 남기고 응답에는 담지 않는다 —
     * 내부 구조가 노출되면 공격 표면이 된다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("예상하지 못한 오류", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }

    private ErrorResponse.FieldError toFieldError(FieldError e) {
        return new ErrorResponse.FieldError(e.getField(), e.getDefaultMessage());
    }
}
