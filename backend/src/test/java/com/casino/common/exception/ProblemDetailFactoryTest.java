package com.casino.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemDetailFactoryTest {

    @Test
    void createsAProblemWithCodeStatusTitleAndDefaultDetail() {
        ProblemDetail problem = ProblemDetailFactory.create(ProblemCode.NOT_FOUND, "/api/v1/things/42");

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Not Found");
        assertThat(problem.getDetail()).isEqualTo("The requested resource does not exist.");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/things/42"));
        assertThat(problem.getProperties()).containsExactly(Map.entry("code", "NOT_FOUND"));
    }

    @Test
    void usesTheGivenDetail() {
        ProblemDetail problem = ProblemDetailFactory.create(ProblemCode.CONCURRENT_MODIFICATION,
                "The profile was changed by another request.", "/api/v1/users/me");

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("The profile was changed by another request.");
    }

    @Test
    void validationFailuresListTheInvalidPropertiesSortedByField() {
        ProblemDetail problem = ProblemDetailFactory.validationFailed(List.of(
                new FieldViolation("password", "must be at least 12 characters long"),
                new FieldViolation("email", "must not be blank"),
                new FieldViolation("email", "must be a well-formed email address")), "/api/v1/auth/register");

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(problem.getProperties().get("errors")).isEqualTo(List.of(
                new FieldViolation("email", "must be a well-formed email address"),
                new FieldViolation("email", "must not be blank"),
                new FieldViolation("password", "must be at least 12 characters long")));
    }

    @Test
    void omitsTheInstanceWhenThePathIsMissingOrNotAUri() {
        assertThat(ProblemDetailFactory.create(ProblemCode.INTERNAL_ERROR, null).getInstance()).isNull();
        assertThat(ProblemDetailFactory.create(ProblemCode.INTERNAL_ERROR, "/a b").getInstance()).isNull();
    }

    @Test
    void everyCodeHasAnErrorStatusAndAGenericDetail() {
        for (ProblemCode code : ProblemCode.values()) {
            if (code.hasFixedStatus()) {
                assertThat(code.status().isError()).as(code.name()).isTrue();
                assertThat(code.allows(code.status().value())).as(code.name()).isTrue();
            }
            assertThat(code.defaultDetail()).as(code.name()).isNotBlank().endsWith(".");
        }
        assertThat(ProblemCode.values()).filteredOn(code -> !code.hasFixedStatus())
                .containsExactly(ProblemCode.CLIENT_ERROR, ProblemCode.SERVER_ERROR);
    }

    @Test
    void mapsAnErrorStatusToACodeThatKeepsIt() {
        assertThat(ProblemCode.forStatus(400)).isEqualTo(ProblemCode.MALFORMED_REQUEST);
        assertThat(ProblemCode.forStatus(401)).isEqualTo(ProblemCode.AUTHENTICATION_REQUIRED);
        assertThat(ProblemCode.forStatus(403)).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(ProblemCode.forStatus(404)).isEqualTo(ProblemCode.NOT_FOUND);
        assertThat(ProblemCode.forStatus(405)).isEqualTo(ProblemCode.METHOD_NOT_ALLOWED);
        assertThat(ProblemCode.forStatus(406)).isEqualTo(ProblemCode.NOT_ACCEPTABLE);
        assertThat(ProblemCode.forStatus(409)).isEqualTo(ProblemCode.CONFLICT);
        assertThat(ProblemCode.forStatus(413)).isEqualTo(ProblemCode.CONTENT_TOO_LARGE);
        assertThat(ProblemCode.forStatus(415)).isEqualTo(ProblemCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ProblemCode.forStatus(500)).isEqualTo(ProblemCode.INTERNAL_ERROR);
        assertThat(ProblemCode.forStatus(503)).isEqualTo(ProblemCode.SERVICE_UNAVAILABLE);
        // No code of their own: the generic fallback of the status class.
        assertThat(ProblemCode.forStatus(410)).isEqualTo(ProblemCode.CLIENT_ERROR);
        assertThat(ProblemCode.forStatus(429)).isEqualTo(ProblemCode.CLIENT_ERROR);
        assertThat(ProblemCode.forStatus(502)).isEqualTo(ProblemCode.SERVER_ERROR);
        for (int status = 400; status < 600; status++) {
            assertThat(ProblemCode.forStatus(status).allows(status)).as("status %d", status).isTrue();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> ProblemCode.forStatus(302));
        assertThatIllegalArgumentException().isThrownBy(() -> ProblemCode.forStatus(600));
    }

    @Test
    void problemsForAStatusKeepIt() {
        ProblemDetail gone = ProblemDetailFactory.forStatus(410, "/api/v1/things/42");
        ProblemDetail nonStandard = ProblemDetailFactory.forStatus(499, "/api/v1/things/42");

        assertThat(gone.getStatus()).isEqualTo(410);
        assertThat(gone.getTitle()).isEqualTo("Gone");
        assertThat(gone.getDetail()).isEqualTo("The request was rejected.");
        assertThat(gone.getProperties()).containsEntry("code", "CLIENT_ERROR");
        assertThat(nonStandard.getStatus()).isEqualTo(499);
        assertThat(nonStandard.getTitle()).isEqualTo("Client Error");
        assertThat(ProblemDetailFactory.forStatus(503, null).getProperties()).containsEntry("code", "SERVICE_UNAVAILABLE");
    }

    @Test
    void genericCodesHaveNoFixedStatus() {
        assertThat(ProblemCode.CLIENT_ERROR.allows(410)).isTrue();
        assertThat(ProblemCode.CLIENT_ERROR.allows(500)).isFalse();
        assertThat(ProblemCode.CONFLICT.allows(410)).isFalse();
        assertThatIllegalStateException().isThrownBy(ProblemCode.SERVER_ERROR::status);
        assertThatIllegalStateException().isThrownBy(() -> ProblemDetailFactory.create(ProblemCode.CLIENT_ERROR, "/a"));
        // Module exceptions must use a specific code.
        assertThatIllegalArgumentException().isThrownBy(() -> new ApiException(ProblemCode.CLIENT_ERROR) { });
    }
}
