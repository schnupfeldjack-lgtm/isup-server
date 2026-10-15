package com.hiki.isup.controller;

import com.hiki.isup.model.response.ErrorResponse;
import com.hiki.isup.service.WaybillVideoSyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/** 使用既有 /api/v1/** API Key 认证；不暴露为公共健康检查。 */
@RestController
@RequestMapping("/api/v1/recordings")
public class RecordingOperationsController {
    private final WaybillVideoSyncService service;

    public RecordingOperationsController(WaybillVideoSyncService service) { this.service = service; }

    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() { return service.diagnostics(); }

    @GetMapping("/retries/{id}")
    public Object retry(@PathVariable String id) throws IOException { return service.inspectRetry(id); }

    @PostMapping("/retries/{id}/release")
    public Object release(@PathVariable String id, @Valid @RequestBody RecoveryRequest request) throws IOException {
        return service.releaseRetry(id, request.expectedRevision(), request.expectedFingerprint(), request.reason());
    }

    @ExceptionHandler(WaybillVideoSyncService.RecoveryConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(WaybillVideoSyncService.RecoveryConflictException e) {
        return ResponseEntity.status(409).body(new ErrorResponse("RECOVERY_CONFLICT", e.getMessage()));
    }

    public record RecoveryRequest(@NotBlank @Size(max = 64) String expectedRevision,
                                  @NotBlank @Size(max = 512) String expectedFingerprint,
                                  @NotBlank @Size(max = 1000) String reason) { }
}
