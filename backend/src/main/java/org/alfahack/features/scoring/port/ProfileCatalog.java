package org.alfahack.features.scoring.port;

import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.*;
import org.alfahack.core.errors.DomainException;

public interface ProfileCatalog {

    Profile get(
        String key
    );

    default String companyKey(String company) {
        return switch (company) {
            case "STABLE" -> "STABLE";
            case "GAP" -> "MANUFACTURING";
            case "REJECT" -> "CONSTRUCTION";
            case "MISSING" -> "MISSING_INFO";
            case "COUNTER" -> "CAFE";
            default -> throw DomainException.bad("COMPANY_DATA_MISSING", "Нет финансового профиля компании");
        };
    }

    List<Map<String, Object>> catalog();
}
