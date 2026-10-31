package com.ordercraft.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
  @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(Map.of("timestamp", Instant.now(), "status", e.status(), "error", HttpStatus.valueOf(e.status()).getReasonPhrase(), "message", e.getMessage()));
  }
  @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest().body(Map.of("timestamp", Instant.now(), "status", 400, "error", "Bad Request", "message", "Request validation failed"));
  }
  @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
    log.error("Unhandled API exception", e);
    return ResponseEntity.internalServerError().body(Map.of("timestamp", Instant.now(), "status", 500, "error", "Internal Server Error", "message", "The request could not be completed"));
  }
}
