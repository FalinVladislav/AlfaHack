package org.alfahack.features.documents.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.documents.port.DocumentUseCases;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class DocumentsController {

    private final HttpAuth auth;
    private final DocumentUseCases docs;

    public DocumentsController(
        HttpAuth auth,
        DocumentUseCases docs
    ) {
        this.auth = auth;
        this.docs = docs;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @PostMapping("/applications/{id}/documents")
    public Map<String, Object> upload(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestParam String request_id,
        @RequestParam MultipartFile file
    ) throws IOException {
        return docs.upload(user(request), id, request_id,
            new org.alfahack.core.domain.UploadedDocument(file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping("/applications/{id}/requests/{requestId}/complete")
    public Map<String, Object> complete(
        HttpServletRequest request,
        @PathVariable String id,
        @PathVariable String requestId
    ) {
        return docs.complete(user(request), id, requestId);
    }

    @GetMapping("/applications/{id}/documents/{documentId}")
    public ResponseEntity<byte[]> download(
        HttpServletRequest request,
        @PathVariable String id,
        @PathVariable String documentId
    ) throws IOException {
        byte[] bytes = docs.download(user(request), id, documentId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=verified-document")
            .contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
    }
}
