package org.alfahack;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ApiException bad(String code, String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }
    public static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Объект не найден"); }
}
