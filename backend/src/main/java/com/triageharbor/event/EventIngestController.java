package com.triageharbor.event;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class EventIngestController {

    static final String INGEST_KEY_HEADER = "X-TriageHarbor-Ingest-Key";

    private final EventIngestService eventIngestService;

    EventIngestController(EventIngestService eventIngestService) {
        this.eventIngestService = eventIngestService;
    }

    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> ingest(
            @RequestHeader(name = INGEST_KEY_HEADER, required = false) String rawIngestKey,
            @Valid @RequestBody EventIngestRequest request) {

        UUID ingestKey = parseIngestKey(rawIngestKey);

        if (ingestKey == null) {
            return unauthorized();
        }

        try {
            eventIngestService.ingest(ingestKey, request);
        } catch (UnknownIngestKeyException exception) {
            return unauthorized();
        }

        return ResponseEntity.accepted().build();
    }

    private static UUID parseIngestKey(String rawIngestKey) {
        if (rawIngestKey == null) {
            return null;
        }

        try {
            return UUID.fromString(rawIngestKey);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
