package com.triageharbor.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class IncidentReadController {

    private final IncidentReadService incidentReadService;

    IncidentReadController(IncidentReadService incidentReadService) {
        this.incidentReadService = incidentReadService;
    }

    @GetMapping(
            path = "/projects/{projectId}/incidents",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<IncidentSummary>> list(
            @PathVariable("projectId") UUID projectId) {

        try {
            return ResponseEntity.ok(incidentReadService.listForProject(projectId));
        } catch (UnknownProjectException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
