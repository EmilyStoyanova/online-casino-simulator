package com.casino.user.exception;

import com.casino.common.exception.ApiException;
import com.casino.common.exception.ProblemCode;

/**
 * A profile update overlapped with another change of the same profile (optimistic locking):
 * {@code 409 CONCURRENT_MODIFICATION}. Nothing of the update was saved; the client reloads the
 * profile and tries again.
 */
public class ConcurrentModificationException extends ApiException {

    public ConcurrentModificationException() {
        super(ProblemCode.CONCURRENT_MODIFICATION, "The profile was changed by another request. Reload it and try again.");
    }
}
