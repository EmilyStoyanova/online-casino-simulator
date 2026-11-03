/**
 * <b>Authentication &amp; authorization</b> module.
 *
 * <p>Registration, login/logout with secure session cookies, password hashing and role-based access control (PLAYER, ADMIN) via Spring Security.
 *
 * <p>Status: security foundation (filter chain, sessions, CSRF, JSON 401/403, password encoder) since
 * W2-04; login and registration throttling ({@code AuthThrottleService}, migration V4) since W2-06;
 * registration with automatic sign-in ({@code POST /api/v1/auth/register}) since W2-07;
 * login and logout follow in Week 2.
 */
package com.casino.auth;
