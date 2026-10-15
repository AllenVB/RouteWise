package com.routewise.common.error;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into the single {@link ApiError} shape.
 *
 * <p>A traceId goes into both the response and the log line, so a user can quote
 * the id from the screen and it can be found in the log without guesswork.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest req) {
        String traceId = newTraceId();
        log.warn("[{}] {} {} -> {}: {}", traceId, req.getMethod(), req.getRequestURI(),
                e.code(), e.getMessage());
        return ResponseEntity.status(e.code().status())
                .body(ApiError.of(e.code(), e.getMessage(), traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e,
                                                     HttpServletRequest req) {
        String traceId = newTraceId();
        List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        log.warn("[{}] {} {} -> doğrulama hatası: {}", traceId, req.getMethod(),
                req.getRequestURI(), fields);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED,
                        "Gönderilen bilgilerde hata var. İşaretli alanları düzeltip tekrar dene.",
                        fields, traceId));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e,
                                                     HttpServletRequest req) {
        String traceId = newTraceId();
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiError.of(ErrorCode.NOT_FOUND, "Kaynak bulunamadı.", traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest req) {
        String traceId = newTraceId();
        // Beklenmeyen hata: tam yığın loglanır, istemciye ayrıntı sızdırılmaz.
        log.error("[{}] {} {} -> beklenmeyen hata", traceId, req.getMethod(),
                req.getRequestURI(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR,
                        "Beklenmeyen bir hata oluştu. Sorun sürerse şu numarayı ilet: " + traceId,
                        traceId));
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
