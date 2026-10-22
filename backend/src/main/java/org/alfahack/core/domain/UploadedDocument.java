package org.alfahack.core.domain;

/** Transport-neutral file data; byte ownership belongs to this value object. */
public record UploadedDocument(
    String filename,
    byte[] content
) {

    public UploadedDocument {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
