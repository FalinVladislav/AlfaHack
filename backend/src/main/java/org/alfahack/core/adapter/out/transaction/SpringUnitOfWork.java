package org.alfahack.core.adapter.out.transaction;

import java.util.function.Supplier;
import org.alfahack.core.transaction.UnitOfWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringUnitOfWork implements UnitOfWork {

    private final TransactionTemplate transactions;

    public SpringUnitOfWork(
        PlatformTransactionManager manager
    ) {
        transactions = new TransactionTemplate(manager);
    }

    public <T> T required(
        Supplier<T> action
    ) {
        return transactions.execute(status -> action.get());
    }
}
