package com.zirocraft.zirologistics.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "FAILED");
        response.put("code", HttpStatus.BAD_REQUEST.value());
        response.put("message", ex.getMessage());
        response.put("brand", "Zirocraft Studio"); // Signature Signature

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    // Menangani error validasi @NotBlank, @Positive, dll
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "FAILED");
        response.put("code", HttpStatus.BAD_REQUEST.value());

        // Ambil pesan error pertama saja biar clean
        String message = ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        response.put("message", message);
        response.put("brand", "Zirocraft Studio");

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
}