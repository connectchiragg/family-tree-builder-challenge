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

class HttpModelClientTest {
  static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
  final ObjectMapper json = new ObjectMapper();
  final StructuredOutput output = new StructuredOutput(factory.getValidator());
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

  HttpModelClient client(int status, String response) throws Exception {
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
    return new HttpModelClient(json, env, metrics, output);
  }

  String reply(String input) {
    return "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"name\":\"respond\",\"input\":"
        + input
        + "}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}";
  }

  @Test
  void sendsGeneratedSchemaAndReturnsValidatedAnswer() throws Exception {
    var client = client(200, reply("{\"message\":\"Hello\"}"));
    var result = client.complete(json.createArrayNode(), "system", ModelResponse.Answer.class);
    assertEquals("Hello", result.message());
    var payload = json.readTree(received.get());
    assertEquals("test-model", payload.path("model").asText());
    assertEquals("test-key", apiKey.get());
    assertEquals(
        output.schema(ModelResponse.Answer.class),
        payload.path("tools").get(0).path("input_schema"));
    assertEquals("respond", payload.path("tool_choice").path("name").asText());
    assertEquals("tool", payload.path("tool_choice").path("type").asText());
    assertTrue(payload.path("tool_choice").path("disable_parallel_tool_use").asBoolean());
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
    var response = client.complete(json.createArrayNode(), "system", ModelResponse.class);
    assertInstanceOf(Plan.CreatePerson.class, response.operations().getFirst());
    assertEquals(1, metrics.get("llm.request").tag("phase", "plan").timer().count());
  }

  @Test
  void missingKeyAndProviderErrorDoNotRetry() throws Exception {
    assertThrows(
        HttpModelClient.Unavailable.class,
        () ->
            new HttpModelClient(json, new MockEnvironment(), metrics, output)
                .complete(json.createArrayNode(), "", ModelResponse.class));
    var client = client(429, "{}");
    var error =
        assertThrows(
            HttpModelClient.Unavailable.class,
            () -> client.complete(json.createArrayNode(), "", ModelResponse.class));
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
      assertThrows(
          HttpModelClient.Unavailable.class,
          () -> client.complete(json.createArrayNode(), "", ModelResponse.class));
      assertEquals(before + 1, calls.get());
      server.stop(0);
    }
  }
}
