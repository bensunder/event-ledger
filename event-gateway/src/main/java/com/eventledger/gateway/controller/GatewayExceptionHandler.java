package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.GatewayErrorResponse;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GatewayErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new GatewayErrorResponse("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<GatewayErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(new GatewayErrorResponse("ERROR", ex.getReason()));
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<GatewayErrorResponse> handleRateLimit(RequestNotPermitted ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new GatewayErrorResponse("RATE_LIMITED",
                        "Too many requests — please slow down and retry shortly"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayErrorResponse> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new GatewayErrorResponse("INTERNAL_ERROR", ex.getMessage()));
    }
}