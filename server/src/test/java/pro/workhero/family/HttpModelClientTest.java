package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class HttpModelClientTest {
  final ObjectMapper json = new ObjectMapper();
  final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  HttpServer server;

  @AfterEach
  void close() {
    if (server != null) server.stop(0);
  }

  @Test
  void sendsAnthropicProtocolAndParsesReply() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var received = new AtomicReference<String>();
    var apiKey = new AtomicReference<String>();
    server.createContext(
        "/v1/messages",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
          byte[] body =
              "{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var env =
        new MockEnvironment()
            .withProperty("ANTHROPIC_API_KEY", "test-key")
            .withProperty("ANTHROPIC_MODEL", "test-model")
            .withProperty(
                "ANTHROPIC_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
    var client = new HttpModelClient(json, env, metrics);
    var result = client.summarize(json.createArrayNode(), json.createArrayNode(), "system");
    assertEquals("Hello", result.path("content").get(0).path("text").asText());
    assertEquals("test-model", json.readTree(received.get()).path("model").asText());
    assertEquals("test-key", apiKey.get());
    assertFalse(json.readTree(received.get()).has("tools"));
    assertEquals(1, metrics.get("llm.request").tag("outcome", "success").timer().count());
    assertEquals(12, metrics.get("llm.tokens").tag("direction", "input").counter().count());
    assertEquals(3, metrics.get("llm.tokens").tag("direction", "output").counter().count());
    assertEquals("none", json.readTree(received.get()).path("tool_choice").path("type").asText());
  }

  @Test
  void missingKeyAndProviderErrorAreExplicit() throws Exception {
    assertThrows(
        HttpModelClient.Unavailable.class,
        () ->
            new HttpModelClient(json, new MockEnvironment(), metrics)
                .complete(json.createArrayNode(), json.createArrayNode(), ""));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/messages",
        e -> {
          e.sendResponseHeaders(429, -1);
          e.close();
        });
    server.start();
    var env =
        new MockEnvironment()
            .withProperty("ANTHROPIC_API_KEY", "test-key")
            .withProperty(
                "ANTHROPIC_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
    var error =
        assertThrows(
            HttpModelClient.Unavailable.class,
            () ->
                new HttpModelClient(json, env, metrics)
                    .complete(json.createArrayNode(), json.createArrayNode(), ""));
    assertTrue(error.getMessage().contains("429"));
    assertEquals(1, metrics.get("llm.request").tag("outcome", "error").timer().count());
    assertNull(metrics.find("llm.tokens").counter());
  }
}
