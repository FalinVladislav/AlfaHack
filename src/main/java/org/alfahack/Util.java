package org.alfahack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

public final class Util {
    private Util() {}
    public static String id() { return UUID.randomUUID().toString(); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    public static BigDecimal money(Object value) {
        try {
            BigDecimal amount = new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) throw new NumberFormatException();
            return amount;
        } catch (Exception ex) { throw ApiException.bad("INVALID_AMOUNT", "Введите положительную сумму"); }
    }
    public static int number(Object value, int min, int max, String field) {
        try { int n = Integer.parseInt(String.valueOf(value)); if (n < min || n > max) throw new NumberFormatException(); return n; }
        catch (Exception ex) { throw ApiException.bad("INVALID_" + field.toUpperCase(), "Неверное значение поля " + field); }
    }
    public static LocalDate date(Object value, String field) {
        try { return LocalDate.parse(String.valueOf(value)); }
        catch (Exception ex) { throw ApiException.bad("INVALID_" + field.toUpperCase(), "Укажите дату в формате ГГГГ-ММ-ДД"); }
    }
    public static String string(Map<String,Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }
    public static String required(Map<String,Object> body, String key) {
        String value = string(body,key);
        if (value == null || value.isBlank()) throw ApiException.bad("MISSING_" + key.toUpperCase(), "Укажите " + key);
        return value;
    }
}
