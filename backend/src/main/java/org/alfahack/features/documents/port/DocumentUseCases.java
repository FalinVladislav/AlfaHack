package org.alfahack.features.documents.port;

import java.util.Map;
import org.alfahack.core.domain.*;

public interface DocumentUseCases {

    Map<String, Object> upload(
        User user,
        String appId,
        String requestId,
        UploadedDocument file
    );

    byte[] download(
        User user,
        String appId,
        String id
    );

    Map<String, Object> complete(
        User user,
        String appId,
        String requestId
    );

    Map<String, Object> info(
        User user,
        String appId,
        String id
    );
}
