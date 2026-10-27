/**
 * <b>Common</b> (shared kernel).
 *
 * <p>Cross-cutting technical concerns used by all modules: web/OpenAPI configuration,
 * time source, platform status endpoint and (later) standardised API errors.
 * Business logic must not live here; modules may depend on {@code common}, but
 * {@code common} must not depend on any business module.
 */
package com.casino.common;
