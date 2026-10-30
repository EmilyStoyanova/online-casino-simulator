package com.casino.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import com.casino.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Verifies that the running application implements {@code openapi.yaml}:
 * <ol>
 *   <li>the specification springdoc generates from the controllers is structurally identical to
 *       the contract for all {@code /api/**} paths;</li>
 *   <li>every documented operation responds with a documented status, media type and body;</li>
 *   <li>the contract is served for Swagger UI.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractIT {

    private final OpenApiContract contract = OpenApiContract.load();

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void implementationMatchesContractForAllApiPaths() throws Exception {
        String generated = perform(get("/v3/api-docs")).getContentAsString(StandardCharsets.UTF_8);

        assertThat(contract.differencesTo(OpenApiContract.fromJson(generated), path -> path.startsWith("/api/")))
                .as("Differences between openapi.yaml (contract) and the implementation")
                .isEmpty();
    }

    @Test
    void everyDocumentedOperationIsVerifiedByThisTest() {
        // A newly implemented operation must also get a runtime check below.
        assertThat(contract.implementedOperations()).containsExactlyInAnyOrder(
                "GET /api/v1/system/status",
                "GET /actuator/health",
                "GET /actuator/health/liveness",
                "GET /actuator/health/readiness",
                "GET /actuator/info");
        // Planned operations cannot be verified at runtime (validateResponse rejects them). When one is
        // implemented, its marker is removed and it moves to the list above. Empty at the end of Week 2.
        assertThat(contract.plannedOperations()).containsExactlyInAnyOrder(
                "GET /api/v1/auth/csrf",
                "POST /api/v1/auth/register",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/logout",
                "GET /api/v1/users/me",
                "PATCH /api/v1/users/me",
                "GET /api/v1/interests");
    }

    @Test
    void systemStatusRespondsAccordingToContract() throws Exception {
        assertConformsToContract("/api/v1/system/status", get("/api/v1/system/status"), 200);
    }

    @Test
    void actuatorEndpointsRespondAccordingToContract() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/info"}) {
            assertConformsToContract(path, get(path).accept(MediaType.APPLICATION_JSON), 200);
        }
    }

    @Test
    void requestsOutsideTheContractReturnProblemDetails() throws Exception {
        MockHttpServletResponse notFound = perform(get("/api/v1/system/unknown"));
        MockHttpServletResponse notAllowed = perform(post("/api/v1/system/status"));

        assertThat(notFound.getStatus()).isEqualTo(404);
        assertThat(notAllowed.getStatus()).isEqualTo(405);
        for (MockHttpServletResponse response : new MockHttpServletResponse[] {notFound, notAllowed}) {
            assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            // Same ProblemDetail schema as the documented 500 response.
            assertThat(contract.validateResponse("/api/v1/system/status", "GET", 500,
                    response.getContentType(), response.getContentAsString(StandardCharsets.UTF_8))).isEmpty();
        }
    }

    @Test
    void contractIsServedAndSelectedByDefaultInSwaggerUi() throws Exception {
        // Real HTTP request: media-type mappings are applied by the embedded Tomcat, not by MockMvc.
        HttpResponse<String> yaml = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/openapi.yaml")).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(yaml.statusCode()).isEqualTo(200);
        assertThat(yaml.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/yaml"));
        assertThat(OpenApiContract.fromYaml(yaml.body()).differencesTo(contract, path -> true)).isEmpty();

        JsonNode swaggerConfig = Json31.mapper().readTree(
                perform(get("/v3/api-docs/swagger-config")).getContentAsString(StandardCharsets.UTF_8));
        assertThat(swaggerConfig.path("urls.primaryName").asText()).isEqualTo("Contract (openapi.yaml)");
        Set<String> urls = new TreeSet<>();
        swaggerConfig.path("urls").forEach(entry -> urls.add(entry.path("url").asText()));
        assertThat(urls).containsExactlyInAnyOrder("/openapi.yaml", "/v3/api-docs");
    }

    private void assertConformsToContract(String path, MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        MockHttpServletResponse response = perform(request);

        assertThat(response.getStatus()).as("%s status", path).isEqualTo(expectedStatus);
        assertThat(contract.validateResponse(path, "GET", response.getStatus(),
                response.getHeader(HttpHeaders.CONTENT_TYPE), response.getContentAsString(StandardCharsets.UTF_8)))
                .as("%s response vs contract", path)
                .isEmpty();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
