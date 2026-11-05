/**
 * <b>User profile</b> module.
 *
 * <p>User accounts, profile data and educational interests/preferences.
 *
 * <p>Status: account data model ({@code UserAccount}, {@code UserAccountRepository}, migration V3)
 * since W2-05; account creation ({@code UserAccountService}, used by registration) since W2-07; login
 * credentials and last login since W2-08; interest catalogue ({@code Interest}, {@code InterestService},
 * {@code GET /api/v1/interests}, migration V5) since W2-09; the signed-in user's profile with the
 * selected interests ({@code UserProfileService}, {@code GET/PATCH /api/v1/users/me}) since W2-10.
 */
package com.casino.user;
