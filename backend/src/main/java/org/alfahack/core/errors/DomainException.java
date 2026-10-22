package org.alfahack.core.errors;

/** Application failure independent of any transport status representation. */
public class DomainException extends RuntimeException {

    public enum Kind {
        INVALID_INPUT, CONFLICT, NOT_FOUND, UNAUTHORIZED, FORBIDDEN
    }
    public final Kind kind;
    public final String code;

    public DomainException(
        Kind kind,
        String code,
        String message
    ) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static DomainException bad(
        String code,
        String message
    ) {
        return new DomainException(Kind.INVALID_INPUT, code, message);
    }

    public static DomainException conflict(
        String code,
        String message
    ) {
        return new DomainException(Kind.CONFLICT, code, message);
    }

    public static DomainException missing() {
        return new DomainException(Kind.NOT_FOUND, "NOT_FOUND", "Объект не найден");
    }

    public static DomainException unauthorized(
        String code,
        String message
    ) {
        return new DomainException(Kind.UNAUTHORIZED, code, message);
    }

    public static DomainException forbidden(
        String code,
        String message
    ) {
        return new DomainException(Kind.FORBIDDEN, code, message);
    }
}
