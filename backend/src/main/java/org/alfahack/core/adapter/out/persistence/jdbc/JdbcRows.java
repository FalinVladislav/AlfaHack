package org.alfahack.core.adapter.out.persistence.jdbc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC-specific values stop at the persistence boundary. */
public final class JdbcRows {

    private JdbcRows() {
    }

    public static Map<String, Object> row(
        Map<String, Object> source
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((
            key,
            value) -> result.put(key, value instanceof java.sql.Date date
                ? date.toLocalDate()
                : value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : value));
        return result;
    }

    public static List<Map<String, Object>> rows(
        List<Map<String, Object>> source
    ) {
        return source.stream().map(JdbcRows::row).toList();
    }
}
