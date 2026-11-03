package com.casino.user.controller;

import java.util.List;

import com.casino.user.dto.InterestResponse;
import com.casino.user.service.InterestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Implements the {@code Interests} tag of {@code openapi.yaml}. Public ({@code SecurityConfig}). */
@RestController
@RequestMapping("/api/v1/interests")
@Tag(name = "Interests")
class InterestController {

    private final InterestService interestService;

    InterestController(InterestService interestService) {
        this.interestService = interestService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listInterests", summary = "List the educational interests")
    @ApiResponse(responseCode = "200", description = "Active interests in display order")
    List<InterestResponse> listInterests() {
        return interestService.listActive();
    }
}
