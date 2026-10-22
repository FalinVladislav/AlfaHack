package org.alfahack.features.products.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.*;

public interface ProductCatalog {

    List<Map<String, Object>> list();

    List<Product> catalog();

    Product get(
        String id
    );

    String reason(
        Product p,
        String purpose,
        BigDecimal amount,
        Integer months,
        Integer days
    );

    Map<String, Object> calculate(
        Product p,
        BigDecimal amount,
        Integer months,
        Integer days
    );
}
