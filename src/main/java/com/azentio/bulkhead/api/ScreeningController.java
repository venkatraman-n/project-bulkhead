package com.azentio.bulkhead.api;

import com.azentio.bulkhead.screening.ScreeningService;
import com.azentio.bulkhead.screening.ScreeningService.ScreeningOutcome;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tenants/{tenantId}/screenings")
public class ScreeningController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ScreeningService service;

    public ScreeningController(ScreeningService service) {
        this.service = service;
    }

    /** 200 with the decision; a retry of an already-screened transaction returns the original decision. */
    @PostMapping
    public ResponseEntity<ScreeningResponse> screen(@PathVariable("tenantId") String tenantId,
                                                    @Valid @RequestBody ScreeningRequest request) {
        ScreeningOutcome outcome = service.screen(tenantId, request);
        return ResponseEntity.ok()
                .header(REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(ScreeningResponse.from(outcome.screening()));
    }
}
