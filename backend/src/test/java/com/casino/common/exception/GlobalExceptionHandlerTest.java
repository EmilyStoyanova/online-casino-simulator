package com.casino.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.casino.contract.OpenApiContract;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every error the {@link GlobalExceptionHandler} produces: status, stable code, Problem Details
 * structure (validated against {@code ProblemDetail} in {@code openapi.yaml}), {@code no-store} and
 * no internal details. Uses a test-only controller, because no production endpoint accepts a body yet.
 */
@WebMvcTest(GlobalExceptionHandlerTest.ProbeController.class)
@Import(GlobalExceptionHandlerTest.ProbeController.class)
class GlobalExceptionHandlerTest {

    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    private static final String SECRET = "secret internal detail";
    private static final String SQL = "insert into users (email, password_hash) values (?, ?)";

    /** Fragments that would reveal implementation details if they appeared in a response. */
    private static final List<String> INTERNALS = List.of(
            SECRET, SQL, "Exception", "com.casino", "java.", "springframework", "jackson", "\tat ");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationErrorsListEveryInvalidPropertyWithoutRejectedValues() throws Exception {
        expectProblem(post("/test/errors/body").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":" ","password":"short-pass"}"""), ProblemCode.VALIDATION_FAILED)
                .andExpect(jsonPath("$.detail").value("The request contains invalid values."))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"))
                .andExpect(jsonPath("$.errors[1].field").value("password"))
                .andExpect(jsonPath("$.errors[1].message").value("must be at least 12 characters long"))
                .andExpect(content -> assertThat(body(content.getResponse())).doesNotContain("short-pass"));
    }

    @Test
    void invalidParametersAreValidationErrors() throws Exception {
        expectProblem(get("/test/errors/param").param("page", "0"), ProblemCode.VALIDATION_FAILED)
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at least 1"))
                // The query string is not part of `instance`.
                .andExpect(jsonPath("$.instance").value("/test/errors/param"));
    }

    @Test
    void unreadableRequestsAreMalformed() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post("/test/errors/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"),
                post("/test/errors/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice\",\"password\":\"long-enough-pass\",\"count\":\"many-wrong\"}"),
                post("/test/errors/body").contentType(MediaType.APPLICATION_JSON),
                get("/test/errors/param"),
                get("/test/errors/param").param("page", "many-wrong"))) {
            expectProblem(request, ProblemCode.MALFORMED_REQUEST)
                    .andExpect(jsonPath("$.detail").value("The request could not be read."))
                    .andExpect(jsonPath("$.errors").doesNotExist())
                    .andExpect(content -> assertThat(body(content.getResponse())).doesNotContain("many-wrong", "Unexpected"));
        }
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        expectProblem(get("/test/errors/unknown"), ProblemCode.NOT_FOUND)
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("The requested resource does not exist."))
                .andExpect(jsonPath("$.instance").value("/test/errors/unknown"));
    }

    @Test
    void apiExceptionsKeepTheirCodeAndDetail() throws Exception {
        expectProblem(get("/test/errors/missing-resource"), ProblemCode.NOT_FOUND)
                .andExpect(jsonPath("$.detail").value("The game round does not exist."));
    }

    @Test
    void unsupportedMethodIsNotAllowed() throws Exception {
        expectProblem(delete("/test/errors/param"), ProblemCode.METHOD_NOT_ALLOWED)
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"));
    }

    @Test
    void unacceptableResponseFormatIsNotAcceptable() throws Exception {
        // The problem itself is still sent as application/problem+json.
        expectProblem(get("/test/errors/param").param("page", "1").accept(MediaType.APPLICATION_XML),
                ProblemCode.NOT_ACCEPTABLE);
    }

    @Test
    void unsupportedRequestContentTypeIsRejected() throws Exception {
        expectProblem(post("/test/errors/body").contentType(MediaType.TEXT_PLAIN).content("name=Alice"),
                ProblemCode.UNSUPPORTED_MEDIA_TYPE)
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString(MediaType.APPLICATION_JSON_VALUE)));
    }

    @Test
    void unexpectedErrorsHideAllInternalDetails() throws Exception {
        for (String path : List.of("/test/errors/failure", "/test/errors/data-access")) {
            expectProblem(get(path), ProblemCode.INTERNAL_ERROR)
                    .andExpect(jsonPath("$.title").value("Internal Server Error"))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred. Please try again later."));
        }
    }

    @Test
    void frameworkErrorsKeepTheirStatus() throws Exception {
        expectProblem(get("/test/errors/too-large"), ProblemCode.CONTENT_TOO_LARGE)
                .andExpect(jsonPath("$.detail").value("The request is too large."));
        expectProblem(get("/test/errors/unavailable"), ProblemCode.SERVICE_UNAVAILABLE)
                .andExpect(jsonPath("$.detail").value(ProblemCode.SERVICE_UNAVAILABLE.defaultDetail()));
        // ResponseStatusException: the status is kept, its reason (SECRET) is never exposed.
        expectProblem(get("/test/errors/status/409"), ProblemCode.CONFLICT);
        expectProblem(get("/test/errors/status/413"), ProblemCode.CONTENT_TOO_LARGE);
        expectProblem(get("/test/errors/status/503"), ProblemCode.SERVICE_UNAVAILABLE);
        // 401/403 keep their status, so W2-04 can rely on it.
        expectProblem(get("/test/errors/status/401"), ProblemCode.AUTHENTICATION_REQUIRED);
        expectProblem(get("/test/errors/status/403"), ProblemCode.ACCESS_DENIED);
    }

    @Test
    void errorsWithoutACodeOfTheirOwnKeepTheirStatusWithAGenericCode() throws Exception {
        expectProblem(get("/test/errors/status/410"), ProblemCode.CLIENT_ERROR, HttpStatus.GONE)
                .andExpect(jsonPath("$.detail").value("The request was rejected."));
        expectProblem(get("/test/errors/status/422"), ProblemCode.CLIENT_ERROR, HttpStatus.UNPROCESSABLE_CONTENT);
        expectProblem(get("/test/errors/status/502"), ProblemCode.SERVER_ERROR, HttpStatus.BAD_GATEWAY)
                .andExpect(jsonPath("$.detail").value(ProblemCode.SERVER_ERROR.defaultDetail()));
    }

    private ResultActions expectProblem(MockHttpServletRequestBuilder request, ProblemCode code) throws Exception {
        return expectProblem(request, code, code.status());
    }

    /**
     * Performs the request and checks what every error response has in common: the expected status and
     * code, {@code application/problem+json}, the contract schema, {@code no-store} and no internal details.
     */
    private ResultActions expectProblem(MockHttpServletRequestBuilder request, ProblemCode code, HttpStatus status)
            throws Exception {
        ResultActions result = mockMvc.perform(request);
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = body(response);

        assertThat(response.getStatus()).as(body).isEqualTo(status.value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", body)).as(body).isEmpty();
        assertThat(body).doesNotContain(INTERNALS);
        return result.andExpect(jsonPath("$.code").value(code.name()))
                // Generic HTTP problem types (about:blank) are omitted, as documented.
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.title").value(status.getReasonPhrase()));
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    record ProbeRequest(
            @NotBlank(message = "must not be blank") String name,
            @NotNull(message = "must not be null") @Size(min = 12, message = "must be at least 12 characters long")
            String password,
            Integer count) {
    }

    static class GameRoundNotFoundException extends ApiException {
        GameRoundNotFoundException() {
            super(ProblemCode.NOT_FOUND, "The game round does not exist.");
        }
    }

    @RestController
    @RequestMapping("/test/errors")
    static class ProbeController {

        @PostMapping("/body")
        String body(@Valid @RequestBody ProbeRequest request) {
            return request.name();
        }

        @GetMapping("/param")
        int param(@RequestParam @Min(value = 1, message = "must be at least 1") int page) {
            return page;
        }

        @GetMapping("/missing-resource")
        void missingResource() {
            throw new GameRoundNotFoundException();
        }

        @GetMapping("/failure")
        void failure() {
            throw new IllegalStateException(SECRET);
        }

        @GetMapping("/data-access")
        void dataAccess() {
            throw new DataIntegrityViolationException("could not execute statement [" + SQL + "]");
        }

        @GetMapping("/status/{status}")
        void status(@PathVariable int status) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(status), SECRET);
        }

        @GetMapping("/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/unavailable")
        void unavailable() {
            throw new AsyncRequestTimeoutException();
        }
    }
}
