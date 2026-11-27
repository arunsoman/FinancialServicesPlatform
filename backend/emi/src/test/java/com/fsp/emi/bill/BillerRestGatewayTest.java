package com.fsp.emi.bill;

import com.fsp.emi.config.EmiProperties;
import com.fsp.shared.rails.RailResult;
import com.fsp.shared.rails.RailTransportException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BILLER_REST adapter wire tests against an in-JVM HTTP stub (no external deps). */
class BillerRestGatewayTest {

    HttpServer server;
    BillerRestGateway gateway;
    Biller biller;
    BillRequest request;
    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    final List<String> requestPaths = new CopyOnWriteArrayList<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<String> body = new AtomicReference<>("{\"status\":\"accepted\",\"rail_ref\":\"r1\"}");
    final AtomicReference<String> retryAfter = new AtomicReference<>(null);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/bills", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!reqBody.isBlank()) requestBodies.add(reqBody);
            var h = exchange.getResponseHeaders();
            if (retryAfter.get() != null) h.add("Retry-After", retryAfter.get());
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        gateway = new BillerRestGateway(new EmiProperties());
        biller = new Biller();
        biller.code = "CITYPOWER";
        biller.transportChannel = Biller.CHANNEL_BILLER_REST;
        biller.endpointCredentialEnvRef = "FSP_BILLER_TEST_KEY"; // not set in env → unauthenticated
        biller.endpointUrl = "http://localhost:" + server.getAddress().getPort();
        request = new BillRequest(java.util.UUID.randomUUID(), "acct-42", "NAD", 12500, "ik-99");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- submit

    @Test
    void mapsAcceptedWithRailRef() {
        var result = (RailResult.Accepted) gateway.submit(biller, request);
        assertThat(result.railRef()).isEqualTo("r1");
    }

    @Test
    void mapsPendingAckAsAcceptedPending() {
        body.set("{\"status\":\"pending\",\"rail_ref\":\"r2\"}");
        assertThat(gateway.submit(biller, request)).isInstanceOf(RailResult.AcceptedPending.class);
    }

    @Test
    void maps422AsPermanentRejection() {
        status.set(422);
        body.set("{\"code\":\"ACCT_UNKNOWN\"}");
        var result = (RailResult.Rejected) gateway.submit(biller, request);
        assertThat(result.code()).isEqualTo("ACCT_UNKNOWN");
        assertThat(result.permanent()).isTrue();
    }

    @Test
    void maps429AsRateLimitedWithRetryAfter() {
        status.set(429);
        retryAfter.set("7");
        var result = (RailResult.Failed) gateway.submit(biller, request);
        assertThat(result.kind()).isInstanceOf(RailResult.FailureKind.RateLimited.class);
        assertThat(((RailResult.FailureKind.RateLimited) result.kind()).movingWindowReset())
            .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void maps5xxAsTemporarilyUnavailable() {
        status.set(503);
        body.set("boom");
        assertThat(gateway.submit(biller, request)).isInstanceOf(RailResult.Failed.class);
    }

    @Test
    void transportFailureSpeaksTheFailureAlphabetAsCommUnreachable() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);
        biller.endpointUrl = "http://localhost:" + port;
        assertThatThrownBy(() -> gateway.submit(biller, request))
            .isInstanceOf(RailTransportException.class)
            .satisfies(e -> assertThat(((RailTransportException) e).kind())
                .isInstanceOf(RailResult.FailureKind.CommUnreachable.class));
    }

    @Test
    void wireBodyCarriesAmountAsStringAndIdempotencyKey() {
        gateway.submit(biller, request);
        assertThat(requestBodies).hasSize(1);
        assertThat(requestBodies.get(0))
            .contains("\"amount_minor\":\"12500\"")
            .contains("\"idempotency_key\":\"ik-99\"")
            .contains("\"currency\":\"NAD\"");
    }

    // ---------------------------------------------------------------- probe

    @Test
    void probeSettledReturnsAccepted() {
        requestPaths.clear();
        status.set(200);
        body.set("{\"status\":\"settled\",\"rail_ref\":\"r3\"}");
        Optional<RailResult> probe = gateway.probe(biller, request, "r3");
        assertThat(probe).isPresent();
        assertThat(probe.get()).isInstanceOf(RailResult.Accepted.class);
        assertThat(requestPaths.get(0)).isEqualTo("/bills/r3");
    }

    @Test
    void probe404MeansNotFoundRejection() {
        status.set(404);
        body.set("{\"error\":\"no such bill\"}");
        Optional<RailResult> probe = gateway.probe(biller, request, "r4");
        assertThat(probe).isPresent();
        assertThat(((RailResult.Rejected) probe.get()).code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void probeInFlightIsInconclusive() {
        status.set(200);
        body.set("{\"status\":\"pending\"}");
        assertThat(gateway.probe(biller, request, "r5")).isEmpty();
    }

    @Test
    void probeTransportFailureIsInconclusiveNotNotFound() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);
        biller.endpointUrl = "http://localhost:" + port;
        assertThat(gateway.probe(biller, request, "r6")).isEmpty();
    }
}
