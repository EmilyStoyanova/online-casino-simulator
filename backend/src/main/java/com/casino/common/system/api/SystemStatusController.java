package com.casino.common.system.api;

import com.casino.common.system.application.SystemStatus;
import com.casino.common.system.application.SystemStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Platform status")
class SystemStatusController {

    private final SystemStatusService systemStatusService;

    SystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    @GetMapping("/status")
    @Operation(summary = "Get platform status", description = "Reports application version and database connectivity.")
    @ApiResponse(responseCode = "200", description = "Platform is up")
    @ApiResponse(responseCode = "503", description = "Platform is unavailable (e.g. database unreachable)")
    ResponseEntity<SystemStatusResponse> status() {
        SystemStatus status = systemStatusService.currentStatus();
        HttpStatus httpStatus = status.isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(SystemStatusResponse.from(status));
    }
}
