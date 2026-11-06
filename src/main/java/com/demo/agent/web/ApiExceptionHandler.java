package com.demo.agent.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一异常出口:所有错误都返回 {error, message} 结构的 JSON,便于调用方解析。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return body(e.getStatusCode().value(), e.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg =
                e.getBindingResult().getFieldErrors().isEmpty()
                        ? "参数不合法"
                        : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return body(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.error("[500] 未处理异常", e);
        return body(500, root.getMessage() == null ? root.getClass().getSimpleName()
                : root.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(int status, String message) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("error", HttpStatus.valueOf(status).getReasonPhrase());
        if (message != null) {
            out.put("message", message);
        }
        return ResponseEntity.status(status).body(out);
    }
}
