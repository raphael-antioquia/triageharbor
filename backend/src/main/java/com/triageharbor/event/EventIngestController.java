package com.triageharbor.event;

import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class EventIngestController {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EventIngestController.class);

    static final String INGEST_KEY_HEADER = "X-TriageHarbor-Ingest-Key";

    private final IngestCredentialResolver ingestCredentialResolver;
    private final EventIngestService eventIngestService;

    EventIngestController(
            IngestCredentialResolver ingestCredentialResolver,
            EventIngestService eventIngestService) {
        this.ingestCredentialResolver = ingestCredentialResolver;
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

        AuthenticatedProject project;

        try {
            project = ingestCredentialResolver.resolve(ingestKey);
        } catch (UnknownIngestKeyException exception) {
            return unauthorized();
        }

        eventIngestService.ingest(project, request);

        return ResponseEntity.accepted().build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Void> handleUnreadableEventBody() {
        LOGGER.warn("Rejected unreadable POST /events request body");
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler({
        DataAccessException.class,
        TransactionException.class
    })
    ResponseEntity<Void> handleDatabaseFailure(RuntimeException exception) {
        LOGGER.error(
                "Database failure during POST /events: {}",
                exception.getClass().getSimpleName());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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
