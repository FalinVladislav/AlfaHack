package org.alfahack.core.transaction;

import java.util.function.Supplier;

public interface UnitOfWork {

    <T> T required(
        Supplier<T> action
    );
}
