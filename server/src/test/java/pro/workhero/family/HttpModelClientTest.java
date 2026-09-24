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
              "{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}"
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
    var client = new HttpModelClient(json, env);
    var result = client.complete(json.createArrayNode(), json.createArrayNode(), "system");
    assertEquals("Hello", result.path("content").get(0).path("text").asText());
    assertEquals("test-model", json.readTree(received.get()).path("model").asText());
    assertEquals("test-key", apiKey.get());
  }

  @Test
  void missingKeyAndProviderErrorAreExplicit() throws Exception {
    assertThrows(
        HttpModelClient.Unavailable.class,
        () ->
            new HttpModelClient(json, new MockEnvironment())
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
                new HttpModelClient(json, env)
                    .complete(json.createArrayNode(), json.createArrayNode(), ""));
    assertTrue(error.getMessage().contains("429"));
  }
}
