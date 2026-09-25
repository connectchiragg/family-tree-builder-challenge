package pro.workhero.family;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class HttpModelClient implements ModelClient {
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final StructuredOutput output;
  private final String key, model, base;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public HttpModelClient(
      ObjectMapper json, Environment env, MeterRegistry metrics, StructuredOutput output) {
    this.json = json;
    this.metrics = metrics;
    this.output = output;
    key = env.getProperty("ANTHROPIC_API_KEY", "");
    model = env.getProperty("ANTHROPIC_MODEL", "anthropic/claude-sonnet-4");
    base = env.getProperty("ANTHROPIC_BASE_URL", "https://openrouter.ai/api").replaceAll("/+$", "");
  }

  @Override
  public <T> T complete(JsonNode messages, String system, Class<T> responseType) {
    if (key.isBlank())
      throw new Unavailable("Set ANTHROPIC_API_KEY in server/.env to enable chat.");
    var sample = Timer.start(metrics);
    var phase = responseType == ModelResponse.Answer.class ? "answer" : "plan";
    var outcome = "error";
    try {
      var payload =
          json.createObjectNode().put("model", model).put("max_tokens", 4096).put("system", system);
      var tool =
          json.createObjectNode()
              .put("name", "respond")
              .put(
                  "description",
                  "Return the requested structured response. This tool does not execute changes.");
      tool.set("input_schema", output.schema(responseType));
      payload.putArray("tools").add(tool);
      payload.set("messages", messages);
      payload
          .putObject("tool_choice")
          .put("type", "tool")
          .put("name", "respond")
          .put("disable_parallel_tool_use", true);
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
      if (!result.path("stop_reason").asText().equals("tool_use")
          || !result.path("content").isArray())
        throw new Unavailable("Model returned incomplete output or declined the request.");
      var uses =
          java.util.stream.StreamSupport.stream(result.path("content").spliterator(), false)
              .filter(b -> b.path("type").asText().equals("tool_use"))
              .toList();
      if (uses.size() != 1 || !uses.getFirst().path("name").asText().equals("respond"))
        throw new Unavailable("Model must return one structured response.");
      T value = output.read(uses.getFirst().path("input"), responseType);
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
