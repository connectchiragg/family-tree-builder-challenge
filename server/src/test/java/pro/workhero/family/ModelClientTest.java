package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class ModelClientTest {
  static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
  final ObjectMapper json = new ObjectMapper();
  final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  final AtomicReference<String> received = new AtomicReference<>();
  final AtomicReference<String> apiKey = new AtomicReference<>();
  final AtomicInteger calls = new AtomicInteger();
  HttpServer server;

  @AfterEach
  void close() {
    if (server != null) server.stop(0);
    metrics.close();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  ModelClient client(int status, String response) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/messages",
        exchange -> {
          calls.incrementAndGet();
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
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
    return new ModelClient(json, env, metrics, factory.getValidator());
  }

  String reply(String input) {
    return "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"name\":\"respond\",\"input\":"
        + input
        + "}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}";
  }

  @Test
  void answerHasNoToolsAndRecordsUsage() throws Exception {
    var client =
        client(
            200,
            "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}");
    var result = client.answer(json.createArrayNode(), "system");
    assertEquals("Hello", result);
    var payload = json.readTree(received.get());
    assertEquals("test-model", payload.path("model").asText());
    assertEquals("test-key", apiKey.get());
    assertFalse(payload.has("tools"));
    assertEquals("none", payload.path("tool_choice").path("type").asText());
    assertEquals(1, calls.get());
    assertEquals(
        1,
        metrics
            .get("llm.request")
            .tag("phase", "answer")
            .tag("outcome", "success")
            .timer()
            .count());
    assertEquals(12, metrics.get("llm.tokens").tag("direction", "input").counter().count());
    assertEquals(3, metrics.get("llm.tokens").tag("direction", "output").counter().count());
  }

  @Test
  void receivesTypedPlanWithNullableMessage() throws Exception {
    var client =
        client(
            200,
            reply(
                "{\"message\":null,\"operations\":[{\"type\":\"create_person\",\"ref\":\"@a\",\"name\":\"Alice\"}]}"));
    var response = client.plan(json.createArrayNode(), "system");
    assertInstanceOf(Plan.CreatePerson.class, response.operations().getFirst());
    var payload = json.readTree(received.get());
    assertEquals(
        json.valueToTree(ModelResponse.schema()),
        payload.path("tools").get(0).path("input_schema"));
    assertEquals("respond", payload.path("tool_choice").path("name").asText());
    assertTrue(payload.path("tool_choice").path("disable_parallel_tool_use").asBoolean());
    assertEquals(1, metrics.get("llm.request").tag("phase", "plan").timer().count());
  }

  @Test
  void missingKeyAndProviderErrorDoNotRetry() throws Exception {
    assertThrows(
        ModelClient.Unavailable.class,
        () ->
            new ModelClient(json, new MockEnvironment(), metrics, factory.getValidator())
                .plan(json.createArrayNode(), ""));
    var client = client(429, "{}");
    var error =
        assertThrows(ModelClient.Unavailable.class, () -> client.plan(json.createArrayNode(), ""));
    assertTrue(error.getMessage().contains("429"));
    assertEquals(1, calls.get());
    assertEquals(1, metrics.get("llm.request").tag("outcome", "error").timer().count());
    assertNull(metrics.find("llm.tokens").counter());
  }

  @Test
  void rejectsTruncatedMultipleWrongAndMalformedResponsesWithoutRetry() throws Exception {
    for (String response :
        new String[] {
          reply("{\"message\":\"Hi\",\"operations\":[]}")
              .replace("\"tool_use\",\"content\"", "\"max_tokens\",\"content\""),
          reply("{\"message\":\"Hi\",\"operations\":[]}").replace("respond", "other_tool"),
          reply("{\"message\":\"Hi\",\"operations\":null}"),
          reply("{\"message\":\"Hi\",\"operations\":[],\"operations\":[]}"),
          "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Saved!\"}]}",
          "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\"},{\"type\":\"tool_use\"}]}",
          "not json"
        }) {
      var client = client(200, response);
      int before = calls.get();
      assertThrows(ModelClient.Unavailable.class, () -> client.plan(json.createArrayNode(), ""));
      assertEquals(before + 1, calls.get());
      assertEquals(before + 1, metrics.get("llm.request").tag("outcome", "error").timer().count());
      server.stop(0);
    }
  }

  @Test
  void answerRejectsMalformedTextAndToolCalls() throws Exception {
    for (String content :
        new String[] {
          "[]",
          "[{\"type\":\"text\",\"text\":42}]",
          "[{\"type\":\"tool_use\",\"name\":\"respond\"}]"
        }) {
      var client = client(200, "{\"stop_reason\":\"end_turn\",\"content\":" + content + "}");
      assertThrows(ModelClient.Unavailable.class, () -> client.answer(json.createArrayNode(), ""));
      server.stop(0);
    }
    assertEquals(3, metrics.get("llm.request").tag("outcome", "error").timer().count());
  }
}
