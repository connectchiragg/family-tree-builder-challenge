package pro.workhero.family;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ModelClient {
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final Validator validator;
  private final String key, model, base;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public ModelClient(
      ObjectMapper json, Environment env, MeterRegistry metrics, Validator validator) {
    this.json =
        json.copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) this.json.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
    this.metrics = metrics;
    this.validator = validator;
    key = env.getProperty("ANTHROPIC_API_KEY", "");
    model = env.getProperty("ANTHROPIC_MODEL", "anthropic/claude-sonnet-4");
    base = env.getProperty("ANTHROPIC_BASE_URL", "https://openrouter.ai/api").replaceAll("/+$", "");
  }

  public ModelResponse plan(JsonNode messages, String system) {
    return send(messages, system, true);
  }

  public String answer(JsonNode messages, String system) {
    return send(messages, system, false).message();
  }

  ModelResponse parse(JsonNode input) {
    try {
      var value = json.treeToValue(input, ModelResponse.class);
      if (value == null || !validator.validate(value).isEmpty())
        throw new IllegalArgumentException();
      return value;
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
      throw new Unavailable(
          "Model returned an invalid response. Nothing from this response was applied.");
    }
  }

  private ModelResponse send(JsonNode messages, String system, boolean planning) {
    if (key.isBlank())
      throw new Unavailable("Set ANTHROPIC_API_KEY in server/.env to enable chat.");
    var sample = Timer.start(metrics);
    var phase = planning ? "plan" : "answer";
    var outcome = "error";
    try {
      var payload =
          json.createObjectNode().put("model", model).put("max_tokens", 4096).put("system", system);
      payload.set("messages", messages);
      if (planning) {
        payload
            .putArray("tools")
            .add(
                json.valueToTree(
                    java.util.Map.of(
                        "name",
                        "respond",
                        "description",
                        "Return one response; this does not execute changes.",
                        "input_schema",
                        ModelResponse.schema())));
        payload
            .putObject("tool_choice")
            .put("type", "tool")
            .put("name", "respond")
            .put("disable_parallel_tool_use", true);
      } else payload.putObject("tool_choice").put("type", "none");
      var body = json.writeValueAsString(payload);
      var request =
          HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .header("anthropic-version", "2023-06-01");
      if (URI.create(base).getHost().equals("openrouter.ai"))
        request.header("Authorization", "Bearer " + key);
      else request.header("x-api-key", key);
      var response =
          http.send(
              request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new Unavailable(
            "Model provider returned HTTP "
                + response.statusCode()
                + ". Check the configured key and model.");
      var result =
          json.reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readTree(response.body());
      for (var direction : java.util.List.of("input", "output")) {
        var tokens = result.path("usage").path(direction + "_tokens");
        if (tokens.isNumber() && tokens.asDouble() >= 0)
          metrics
              .counter("llm.tokens", "phase", phase, "direction", direction)
              .increment(tokens.asDouble());
      }
      if (!result.path("stop_reason").asText().equals(planning ? "tool_use" : "end_turn")
          || !result.path("content").isArray())
        throw new Unavailable("Model returned incomplete output or declined the request.");
      var uses =
          java.util.stream.StreamSupport.stream(result.path("content").spliterator(), false)
              .filter(b -> b.path("type").asText().equals("tool_use"))
              .toList();
      ModelResponse value;
      if (planning) {
        if (uses.size() != 1 || !uses.getFirst().path("name").asText().equals("respond"))
          throw new Unavailable("Model must return one structured response.");
        value = parse(uses.getFirst().path("input"));
      } else {
        if (!uses.isEmpty()) throw new Unavailable("Unexpected tool request during explanation.");
        var text = new StringBuilder();
        for (var block : result.path("content")) {
          if (!block.path("type").asText().equals("text")) continue;
          if (!block.path("text").isTextual()) throw new Unavailable("Invalid model answer.");
          if (!text.isEmpty()) text.append('\n');
          text.append(block.path("text").asText());
        }
        value =
            parse(
                json.createObjectNode()
                    .put("message", text.toString().strip())
                    .set("operations", json.createArrayNode()));
      }
      outcome = "success";
      return value;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Unavailable("Model request interrupted.");
    } catch (java.io.IOException e) {
      throw new Unavailable("Model provider unavailable or timed out.");
    } finally {
      sample.stop(metrics.timer("llm.request", "phase", phase, "outcome", outcome));
    }
  }

  public static class Unavailable extends RuntimeException {
    public Unavailable(String message) {
      super(message);
    }
  }
}
