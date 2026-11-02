package com.casino.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.casino.TestcontainersConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The client address that the application sees ({@code request.getRemoteAddr()}, used for throttling)
 * over real HTTP with the embedded Tomcat: a client cannot choose it with {@code X-Forwarded-For}.
 * The probe filter runs last, so it sees the request exactly as controllers do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ClientAddressIT.ProbeConfiguration.class})
class ClientAddressIT {

    private static final String LOOPBACK = "127.0.0.1";

    @LocalServerPort
    private int port;

    @Autowired
    private ClientAddressProbe probe;

    @Test
    void forwardedHeadersOfClientsAreIgnoredByDefault() throws Exception {
        send(port, "198.51.100.9");

        // The TCP peer, not the forged header.
        assertThat(probe.remoteAddr).isEqualTo(LOOPBACK);
        assertThat(probe.secure).isFalse();
    }

    /** {@code native} as in production, but the connecting peer is not a listed proxy. */
    @Nested
    @TestPropertySource(properties = {
            "SERVER_FORWARD_HEADERS_STRATEGY=native",
            "server.tomcat.remoteip.internal-proxies=10[.]1[.]2[.]3"})
    class BehindNativeHandlingFromAnUntrustedPeer {

        @LocalServerPort
        private int nativePort;

        @Autowired
        private ClientAddressProbe nativeProbe;

        @Test
        void forwardedHeadersAreIgnored() throws Exception {
            send(nativePort, "198.51.100.9");

            assertThat(nativeProbe.remoteAddr).isEqualTo(LOOPBACK);
            assertThat(nativeProbe.secure).isFalse();
        }
    }

    /** {@code native} behind a trusted proxy (here the loopback address stands for the platform's proxy). */
    @Nested
    @TestPropertySource(properties = {
            "SERVER_FORWARD_HEADERS_STRATEGY=native",
            "server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1"})
    class BehindATrustedProxy {

        @LocalServerPort
        private int proxiedPort;

        @Autowired
        private ClientAddressProbe proxiedProbe;

        @Test
        void theRightMostUntrustedEntryIsTheClient() throws Exception {
            // The client forged the first entry; the proxy appended the address it saw.
            send(proxiedPort, "6.6.6.6, 203.0.113.5");

            assertThat(proxiedProbe.remoteAddr).isEqualTo("203.0.113.5");
            assertThat(proxiedProbe.secure).isTrue();
        }
    }

    private static void send(int port, String forwardedFor) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ":" + port + "/api/v1/system/status"))
                .header("X-Forwarded-For", forwardedFor)
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        }
    }

    /** Records the address and scheme of the last request. */
    static class ClientAddressProbe extends OncePerRequestFilter {

        volatile String remoteAddr;
        volatile boolean secure;

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            remoteAddr = request.getRemoteAddr();
            secure = request.isSecure();
            chain.doFilter(request, response);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ClientAddressProbe clientAddressProbe() {
            return new ClientAddressProbe();
        }

        @Bean
        FilterRegistrationBean<ClientAddressProbe> clientAddressProbeRegistration(ClientAddressProbe probe) {
            FilterRegistrationBean<ClientAddressProbe> registration = new FilterRegistrationBean<>(probe);
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }
    }
}
