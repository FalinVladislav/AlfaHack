package org.alfahack;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,Object>> api(ApiException ex) {
        return ResponseEntity.status(ex.status).body(Map.of("code", ex.code, "message", ex.getMessage()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String,Object>> upload() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("code", "FILE_TOO_LARGE", "message", "Размер файла превышает 10 МБ"));
    }
}
