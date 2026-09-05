package com.datagent.agentv2.artifact;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.datagent.agentv2.artifact.application.ArtifactDownload;
import com.datagent.agentv2.artifact.application.ArtifactQueryService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 会话级 Artifact 下载门面；所有不存在、越权和非 READY 情形统一收敛为 404。 */
@RestController
@RequestMapping("/api/v1/agent-sessions/{sessionId}/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactQueryService artifacts;

    @GetMapping("/{artifactId}/content")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String sessionId, @PathVariable String artifactId) {
        ArtifactDownload download =
                artifacts
                        .findDownload(sessionId, artifactId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            download.validate();
        } catch (IOException integrityFailure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Artifact integrity validation failed");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mediaType()))
                .contentLength(download.sizeBytes())
                .eTag('"' + download.sha256() + '"')
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.displayName(), UTF_8)
                                .build()
                                .toString())
                .body(download::copyTo);
    }
}
