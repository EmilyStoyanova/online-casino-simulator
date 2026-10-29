package com.casino.common.system.controller;

import com.casino.common.system.dto.ComponentStatus;
import com.casino.common.system.dto.SystemStatusResponse;
import com.casino.common.system.service.SystemStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Implements the {@code System} tag of {@code openapi.yaml}. */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System")
class SystemStatusController {

    private final SystemStatusService systemStatusService;

    SystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSystemStatus", summary = "Get platform status")
    @ApiResponse(responseCode = "200", description = "Platform is up")
    @ApiResponse(responseCode = "503", description = "Platform is unavailable (database unreachable)")
    ResponseEntity<SystemStatusResponse> getSystemStatus() {
        SystemStatusResponse status = systemStatusService.currentStatus();
        HttpStatus httpStatus = status.status() == ComponentStatus.UP ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(status);
    }
}
