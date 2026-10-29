/**
 * <b>Common</b> (shared kernel).
 *
 * <p>Cross-cutting technical concerns used by all modules: web/OpenAPI configuration
 * ({@code config}), centralised API error handling ({@code exception}), the time source and
 * the platform status endpoint ({@code system}).
 * Business logic must not live here; modules may depend on {@code common}, but
 * {@code common} must not depend on any business module.
 */
package com.casino.common;
