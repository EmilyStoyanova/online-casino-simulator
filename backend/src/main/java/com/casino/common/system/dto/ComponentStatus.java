package com.casino.common.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Availability of the platform or one of its dependencies. */
@Schema(name = "ComponentStatus", enumAsRef = true)
public enum ComponentStatus {
    UP,
    DOWN
}
