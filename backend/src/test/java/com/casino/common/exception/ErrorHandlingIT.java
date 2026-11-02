package com.casino.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.casino.TestcontainersConfiguration;
import com.casino.contract.OpenApiContract;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Error handling over real HTTP (embedded Tomcat), including errors that never reach a controller:
 * the container forwards them to {@code /error}, which must answer with the same Problem Details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ErrorHandlingIT {

    private static final String SECRET = "secret filter failure";
    private static final String BROWSER = "text/html,application/xhtml+xml";
    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @LocalServerPort
    private int port;

    @Test
    void exceptionInAFilterIsAGenericInternalError() throws Exception {
        JsonNode problem = expectProblem(send("/test/filter/throw", MediaType.APPLICATION_JSON_VALUE), ProblemCode.INTERNAL_ERROR);

        assertThat(problem.path("instance").asText()).isEqualTo("/test/filter/throw");
        assertThat(problem.path("detail").asText()).isEqualTo(ProblemCode.INTERNAL_ERROR.defaultDetail());
    }

    @Test
    void errorSentByAFilterKeepsItsStatusAndGetsItsCode() throws Exception {
        Map<Integer, ProblemCode> expected = Map.of(
                400, ProblemCode.MALFORMED_REQUEST,
                401, ProblemCode.AUTHENTICATION_REQUIRED,
                403, ProblemCode.ACCESS_DENIED,
                409, ProblemCode.CONFLICT,
                413, ProblemCode.CONTENT_TOO_LARGE,
                503, ProblemCode.SERVICE_UNAVAILABLE,
                418, ProblemCode.CLIENT_ERROR,
                502, ProblemCode.SERVER_ERROR);

        for (Map.Entry<Integer, ProblemCode> entry : expected.entrySet()) {
            JsonNode problem = expectProblem(send("/test/filter/reject?status=" + entry.getKey(), BROWSER),
                    entry.getValue(), entry.getKey());
            assertThat(problem.path("instance").asText()).isEqualTo("/test/filter/reject");
        }
    }

    @Test
    void directRequestToTheErrorPathIsNotFound() throws Exception {
        expectProblem(send("/error", MediaType.APPLICATION_JSON_VALUE), ProblemCode.NOT_FOUND);
    }

    @Test
    void browserRequestsAlsoGetProblemDetails() throws Exception {
        // Spring Security's 401 (entry point) is rendered by the same error handling.
        expectProblem(send("/api/v1/system/unknown", BROWSER), ProblemCode.AUTHENTICATION_REQUIRED);
    }

    private HttpResponse<String> send(String path, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.ACCEPT, accept)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode expectProblem(HttpResponse<String> response, ProblemCode code) throws IOException {
        return expectProblem(response, code, code.status().value());
    }

    private static JsonNode expectProblem(HttpResponse<String> response, ProblemCode code, int status) throws IOException {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).hasValue("no-store");
        assertThat(CONTRACT.validateSchema("ProblemDetail", response.body())).as(response.body()).isEmpty();
        assertThat(response.body()).doesNotContain(SECRET, "Exception", "trace", "com.casino");
        JsonNode problem = Json31.mapper().readTree(response.body());
        assertThat(problem.path("code").asText()).isEqualTo(code.name());
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        return problem;
    }

    /**
     * Detected automatically as a nested test configuration; never part of other test contexts. The
     * filters run before Spring Session and Spring Security, so the forwarded error dispatch to
     * {@code /error} passes the security filter chain, where {@code /error} must be permitted.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingFilters {

        @Bean
        FilterRegistrationBean<Filter> throwingFilter() {
            return register("/test/filter/throw", (request, response, chain) -> {
                throw new IllegalStateException(SECRET);
            });
        }

        @Bean
        FilterRegistrationBean<Filter> rejectingFilter() {
            return register("/test/filter/reject", (request, response, chain) ->
                    ((HttpServletResponse) response).sendError(Integer.parseInt(request.getParameter("status")), SECRET));
        }

        private static FilterRegistrationBean<Filter> register(String path, Filter filter) {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns(path);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
