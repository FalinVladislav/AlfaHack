package org.alfahack.features.documents.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.alfahack.core.domain.UploadedDocument;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.transaction.UnitOfWork;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.alfahack.features.documents.port.DocumentContent;
import org.alfahack.features.documents.port.DocumentsRepository;

public class Documents implements org.alfahack.features.documents.port.DocumentUseCases {

    private final UnitOfWork unitOfWork;
    private final DocumentsRepository repository;
    private final ApplicationUseCases applications;
    private final DocumentContent content;

    public Documents(
        UnitOfWork unitOfWork,
        DocumentsRepository repository,
        ApplicationUseCases applications,
        DocumentContent content
    ) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.applications = applications;
        this.content = content;
    }

    public Map<String, Object> upload(
        User user,
        String appId,
        String requestId,
        UploadedDocument file
    ) {
        return unitOfWork.required(() -> {
            user.client();
            Map<String, Object> a = applications.raw(user, appId);
            if (!"NEEDS_INFO".equals(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Документ сейчас не запрошен");
            var rows = repository.request(requestId, appId);
            if (rows.isEmpty())
                throw DomainException.missing();
            Map<String, Object> request = rows.getFirst();
            if (file.content().length == 0 || file.content().length > 100_000)
                throw DomainException.bad("INVALID_FILE", "Выберите подготовленную выписку CSV");
            String filename = file.filename() == null ? "" : file.filename();
            byte[] bytes = file.content();
            if (!filename.toLowerCase().endsWith(".csv")
                || !new String(bytes, StandardCharsets.UTF_8).matches("(?s)[\\x09\\x0A\\x0D\\x20-\\x7EА-Яа-яЁё]*"))
                throw DomainException.bad("INVALID_FILE_TYPE", "Ожидается CSV с синтетическими данными");
            byte[] expected = content.statement(user.company());
            if (expected == null || !Util.hash(bytes).equals(Util.hash(expected)))
                throw DomainException.bad("SYNTHETIC_ONLY",
                    "Файл не соответствует подготовленному синтетическому набору. Он не сохранён");
            String id = Util.id();
            repository.insert(id, requestId, user.company(), "financial_statement.csv", "text/csv", Util.hash(bytes),
                "SEED", true, LocalDateTime.now());
            repository.verify(requestId);
            return Map.of("id", id, "request_id", requestId, "status", "VERIFIED", "message", "Сведения подтверждены");

        });
    }

    public byte[] download(
        User user,
        String appId,
        String id
    ) {
        applications.raw(user, appId);
        var rows = repository.download(id, appId);
        if (rows.isEmpty())
            throw DomainException.missing();
        Map<String, Object> doc = rows.getFirst();
        if (user.role().equals("CLIENT") && !user.company().equals(doc.get("company_id")))
            throw DomainException.missing();
        return content.statement(doc.get("company_id").toString());
    }

    public Map<String, Object> complete(
        User user,
        String appId,
        String requestId
    ) {
        user.client();
        applications.raw(user, appId);
        var rows = repository.requestStatus(requestId, appId);
        if (rows.isEmpty())
            throw DomainException.missing();
        if (!"VERIFIED".equals(rows.getFirst().get("status")))
            throw DomainException.conflict("DOCUMENT_NOT_VERIFIED", "Документ ещё не подтверждён");
        return applications.check(new User("SYSTEM", "STAFF", null, ""), appId, null);
    }

    public Map<String, Object> info(
        User user,
        String appId,
        String id
    ) {
        applications.raw(user, appId);
        var rows = repository.metadata(id, appId);
        if (rows.isEmpty())
            throw DomainException.missing();
        return rows.getFirst();
    }
}
