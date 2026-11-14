package com.casino.wallet.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import com.casino.wallet.exception.InvalidCursorException;

/**
 * The opaque {@code nextCursor} of the transaction history ({@code docs/plans/week-03.md} §6.3):
 * URL-safe Base64 without padding of {@code "v1:<id>"}, where {@code id} is the last (oldest) entry of
 * the page. The next page holds the entries of the session's wallet with a lower id.
 *
 * <p>It is a position, not a credential: the history query always selects the session's wallet, so a
 * cursor taken from another wallet can only select entries of the session's own wallet. Only the
 * exact form this class issues is accepted, so clients cannot build or vary cursors. At most 30
 * characters, within the contract's 64.
 */
final class TransactionCursor {

    private static final String VERSION = "v1:";
    private static final int MAX_LENGTH = 64;
    private static final Pattern ALPHABET = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern PAYLOAD = Pattern.compile("^v1:[1-9][0-9]{0,18}$");

    private TransactionCursor() {
    }

    /** The cursor of the page that follows the entry {@code id}. */
    static String after(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Entry ids are positive");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString((VERSION + id).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The entry id a cursor continues after.
     *
     * @throws InvalidCursorException if the value is not a cursor issued by {@link #after}
     */
    static long decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH || !ALPHABET.matcher(cursor).matches()) {
            throw new InvalidCursorException();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException ex) {
            throw new InvalidCursorException();
        }
        if (!PAYLOAD.matcher(payload).matches()) {
            throw new InvalidCursorException();
        }
        long id;
        try {
            id = Long.parseLong(payload.substring(VERSION.length()));
        } catch (NumberFormatException ex) {
            // More than Long.MAX_VALUE.
            throw new InvalidCursorException();
        }
        // Only the canonical encoding: no other spelling of the same position is accepted.
        if (!after(id).equals(cursor)) {
            throw new InvalidCursorException();
        }
        return id;
    }
}
