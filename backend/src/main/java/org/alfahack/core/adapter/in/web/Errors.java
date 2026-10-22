package org.alfahack.core.adapter.in.web;

import java.util.Map;
import org.alfahack.core.errors.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, Object>> domain(
        DomainException ex
    ) {
        int status = switch (ex.kind) {
            case INVALID_INPUT -> 422;
            case CONFLICT -> 409;
            case NOT_FOUND -> 404;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
        };
        return ResponseEntity.status(status).body(Map.of("code", ex.code, "message", ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> upload() {
        return ResponseEntity.status(413)
            .body(Map.of("code", "FILE_TOO_LARGE", "message", "Размер файла превышает 10 МБ"));
    }
}
