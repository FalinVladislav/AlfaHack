package org.alfahack.features.finance.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.alfahack.features.finance.port.CreditAccountUseCases;
import org.alfahack.core.domain.Util;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {

    private final HttpAuth auth;
    private final FinanceUseCases finance;
    private final CreditAccountUseCases creditAccounts;
    public FinanceController(
        HttpAuth auth,
        FinanceUseCases finance,
        CreditAccountUseCases creditAccounts
    ) {
        this.auth = auth;
        this.finance = finance;
        this.creditAccounts = creditAccounts;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        return finance.overview(auth.user(request));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(HttpServletRequest request) {
        return finance.summary(auth.user(request));
    }

    @GetMapping("/loans")
    public List<Map<String, Object>> loans(HttpServletRequest request) {
        return finance.loans(auth.user(request));
    }

    @GetMapping("/activity")
    public Map<String, Object> activity(
        HttpServletRequest request,
        @RequestParam(defaultValue = "") String from,
        @RequestParam(defaultValue = "") String to,
        @RequestParam(defaultValue = "ALL") String type,
        @RequestParam(defaultValue = "") String category,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int size
    ) {
        return finance.activity(auth.user(request), from, to, type, category, page, size);
    }

    @GetMapping("/credit-load")
    public Map<String, Object> creditLoad(
        HttpServletRequest request,
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "ALL") String source,
        @RequestParam(defaultValue = "") String dueFrom,
        @RequestParam(defaultValue = "") String dueTo,
        @RequestParam(defaultValue = "1") int loanPage,
        @RequestParam(defaultValue = "1") int paymentPage,
        @RequestParam(defaultValue = "8") int size
    ) {
        return creditAccounts.load(auth.user(request), search, source, dueFrom, dueTo,
            loanPage, paymentPage, size);
    }

    @PutMapping("/loans/{source}/{id}/name")
    public Map<String, Object> renameLoan(
        HttpServletRequest request,
        @PathVariable String source,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return creditAccounts.rename(auth.user(request), source, id, Util.string(body, "name"));
    }

    @PostMapping("/loans/{source}/{id}/payments")
    public Map<String, Object> payLoan(
        HttpServletRequest request,
        @PathVariable String source,
        @PathVariable String id,
        @RequestBody Map<String, Object> body,
        @RequestHeader(value = "Idempotency-Key", required = false) String key
    ) {
        return creditAccounts.pay(auth.user(request), source, id, key, Util.string(body, "expected_due_date"));
    }
}
