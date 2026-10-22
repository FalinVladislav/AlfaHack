package org.alfahack.features.products.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.products.port.ProductCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProductsController {

    private final HttpAuth auth;
    private final ProductCatalog products;

    public ProductsController(
        HttpAuth auth,
        ProductCatalog products
    ) {
        this.auth = auth;
        this.products = products;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(
        HttpServletRequest request
    ) {
        user(request);
        return products.list();
    }
}
