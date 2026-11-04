package com.casino.contract;

import static com.casino.SessionCookies.cookie;
import static com.casino.SessionCookies.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.casino.RealCsrfToken;
import com.casino.TestAccounts;
import com.casino.TestcontainersConfiguration;
import com.casino.common.exception.ProblemCode;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * AC-0.6: the {@code security} of every operation in {@code openapi.yaml} matches the runtime access
 * rules. Operations without {@code sessionCookie} are reachable without a session (with a CSRF token
 * for unsafe methods); all others answer {@code 401} without one. Every unsafe operation needs the
 * CSRF token, with and without a session. Driven by the contract, so a new operation is checked
 * without changing this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccessRulesContractIT {

    private static final String SESSION = "sessionCookie";
    private static final String CSRF = "csrfHeader";
    private static final String PASSWORD = "correct-horse-battery-8e1b";
    private static final Set<HttpMethod> UNSAFE = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final OpenApiContract contract = OpenApiContract.load();
    /** A client IP per request, so no throttling counter is shared. */
    private final AtomicInteger clientIp = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestAccounts accounts;
    private RealCsrfToken csrf;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(jdbcTemplate, passwordEncoder);
        accounts.active("alice@example.com", PASSWORD);
        csrf = RealCsrfToken.fetch(mockMvc);
    }

    @AfterEach
    void deleteRows() {
        accounts.deleteAll();
    }

    @Test
    void theContractDeclaresSessionsAndCsrfTokensConsistently() {
        for (DocumentedOperation operation : operations()) {
            // Every unsafe operation documents the CSRF token in each of its requirements.
            if (operation.unsafe()) {
                assertThat(operation.security()).as(operation.name()).isNotEmpty()
                        .allSatisfy(requirement -> assertThat(requirement).containsKey(CSRF));
            } else {
                assertThat(operation.security()).as(operation.name())
                        .allSatisfy(requirement -> assertThat(requirement).doesNotContainKey(CSRF));
            }
        }
        // Administrative operations follow in Week 10 (AdminAuthorizationIT covers the rule until then).
        assertThat(contract.spec().getPaths().keySet()).noneMatch(path -> path.startsWith("/api/v1/admin"));
    }

    @Test
    void operationsWithoutASessionRequirementAreReachableAnonymouslyAndAllOthersAnswer401() throws Exception {
        List<String> checked = new ArrayList<>();
        for (DocumentedOperation operation : operations()) {
            MockHttpServletResponse response = perform(operation.allowedRequest());
            if (operation.needsSession()) {
                expectProblem(response, ProblemCode.AUTHENTICATION_REQUIRED, operation);
            } else {
                assertThat(response.getStatus()).as("%s without a session: %s", operation.name(), body(response))
                        .isNotIn(401, 403);
            }
            checked.add(operation.name());
        }
        assertThat(checked).contains("GET /api/v1/users/me", "PATCH /api/v1/users/me", "POST /api/v1/auth/login");
    }

    @Test
    void everyOperationIsReachableWithASession() throws Exception {
        for (DocumentedOperation operation : operations()) {
            // A new session per operation: logout ends it.
            Cookie session = signIn();
            MockHttpServletResponse response = perform(operation.allowedRequest().cookie(session));

            assertThat(response.getStatus()).as("%s with a session: %s", operation.name(), body(response)).isNotIn(401, 403);
        }
    }

    @Test
    void unsafeOperationsRequireACsrfTokenWithAndWithoutASession() throws Exception {
        Cookie session = signIn();
        for (DocumentedOperation operation : operations()) {
            if (!operation.unsafe()) {
                continue;
            }
            expectProblem(perform(operation.request()), ProblemCode.CSRF_TOKEN_INVALID, operation);
            expectProblem(perform(operation.request().cookie(session)), ProblemCode.CSRF_TOKEN_INVALID, operation);
        }
        // The session was not ended by the rejected logout.
        assertThat(perform(request(HttpMethod.GET, "/api/v1/users/me").cookie(session)).getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ helpers

    private List<DocumentedOperation> operations() {
        List<DocumentedOperation> operations = new ArrayList<>();
        contract.spec().getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
                operations.add(new DocumentedOperation(path, method, operation))));
        assertThat(operations).isNotEmpty();
        return operations;
    }

    private Cookie signIn() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"" + PASSWORD + "\"}")
                        .with(csrf).with(this::fromNewClientIp))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return cookie(sessionCookie(response));
    }

    /** Performs the request from a client IP of its own. */
    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(this::fromNewClientIp)).andReturn().getResponse();
    }

    private MockHttpServletRequest fromNewClientIp(MockHttpServletRequest request) {
        request.setRemoteAddr("198.51.100." + clientIp.getAndIncrement() % 250);
        return request;
    }

    private static void expectProblem(MockHttpServletResponse response, ProblemCode code, DocumentedOperation operation)
            throws Exception {
        String body = body(response);
        assertThat(response.getStatus()).as("%s: %s", operation.name(), body).isEqualTo(code.status().value());
        assertThat(Json31.mapper().readTree(body).path("code").asText()).as(operation.name()).isEqualTo(code.name());
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    /** One operation of the contract and a minimal request for it. */
    private final class DocumentedOperation {

        private final String path;
        private final HttpMethod method;
        private final Operation operation;

        DocumentedOperation(String path, PathItem.HttpMethod method, Operation operation) {
            this.path = path;
            this.method = HttpMethod.valueOf(method.name());
            this.operation = operation;
        }

        String name() {
            return method + " " + path;
        }

        List<SecurityRequirement> security() {
            return operation.getSecurity() == null ? List.of() : operation.getSecurity();
        }

        boolean needsSession() {
            return security().stream().anyMatch(requirement -> requirement.containsKey(SESSION));
        }

        boolean unsafe() {
            return UNSAFE.contains(method);
        }

        /**
         * The operation with an empty JSON body when it has one: enough to pass the access rules, which
         * run before validation ({@code 400} still proves that the request was let through).
         */
        MockHttpServletRequestBuilder request() {
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path).accept(MediaType.APPLICATION_JSON);
            if (operation.getRequestBody() != null) {
                request.contentType(MediaType.APPLICATION_JSON).content("{}");
            }
            return request;
        }

        /** Like {@link #request()}, with the CSRF token for an unsafe method: what the access rules let through. */
        MockHttpServletRequestBuilder allowedRequest() {
            return unsafe() ? request().with(csrf) : request();
        }
    }
}
