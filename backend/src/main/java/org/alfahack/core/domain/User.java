package org.alfahack.core.domain;

import org.alfahack.core.errors.DomainException;

public record User(
    String key,
    String role,
    String company,
    String csrf
) {

    public void client() {
        if (!role.equals("CLIENT"))
            throw new DomainException(DomainException.Kind.FORBIDDEN, "FORBIDDEN", "Действие доступно клиенту");
    }

    public void staff() {
        if (!role.equals("STAFF"))
            throw new DomainException(DomainException.Kind.FORBIDDEN, "FORBIDDEN", "Действие доступно сотруднику");
    }
}
