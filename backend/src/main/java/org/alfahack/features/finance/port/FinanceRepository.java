package org.alfahack.features.finance.port;

import java.util.List;
import java.util.Map;

public interface FinanceRepository {

    Map<String, Object> company(String companyId);

    List<Map<String, Object>> monthlyTotals(String companyId);

    List<Map<String, Object>> transactions(String companyId);

    Map<String, Object> activity(
        String companyId,
        java.time.LocalDate from,
        java.time.LocalDate to,
        String type,
        String category,
        int page,
        int size
    );

    List<Map<String, Object>> balances(String companyId);

    List<Map<String, Object>> existingLoans(String companyId);

    List<Map<String, Object>> issuedLoans(String companyId);

    default List<Map<String, Object>> unpaidSchedule(String companyId) {
        return List.of();
    }

    List<Map<String, Object>> products();

    List<Map<String, Object>> needs(String companyId);
}
