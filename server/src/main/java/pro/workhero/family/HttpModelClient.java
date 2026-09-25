package pro.workhero.family;

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
  private final String key, model, base;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public HttpModelClient(ObjectMapper json, Environment env, MeterRegistry metrics) {
    this.json = json;
    this.metrics = metrics;
    key = env.getProperty("ANTHROPIC_API_KEY", "");
    model = env.getProperty("ANTHROPIC_MODEL", "anthropic/claude-sonnet-4");
    base = env.getProperty("ANTHROPIC_BASE_URL", "https://openrouter.ai/api").replaceAll("/+$", "");
  }

  @Override
  public JsonNode complete(JsonNode messages, JsonNode tools, String system) {
    return send(messages, tools, system, false);
  }

  @Override
  public JsonNode summarize(JsonNode messages, JsonNode tools, String system) {
    return send(messages, tools, system, true);
  }

  private JsonNode send(JsonNode messages, JsonNode tools, String system, boolean explainOnly) {
    if (key.isBlank())
      throw new Unavailable("Set ANTHROPIC_API_KEY in server/.env to enable chat.");
    var sample = Timer.start(metrics);
    var phase = explainOnly ? "answer" : "plan";
    var outcome = "error";
    try {
      var payload =
          json.createObjectNode().put("model", model).put("max_tokens", 4096).put("system", system);
      if (!explainOnly) payload.set("tools", tools);
      payload.set("messages", messages);
      payload.putObject("tool_choice").put("type", explainOnly ? "none" : "auto");
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
      var result = json.readTree(response.body());
      for (var direction : java.util.List.of("input", "output")) {
        var tokens = result.path("usage").path(direction + "_tokens");
        if (tokens.isNumber() && tokens.asDouble() >= 0)
          metrics
              .counter("llm.tokens", "phase", phase, "direction", direction)
              .increment(tokens.asDouble());
      }
      outcome = "success";
      return result;
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
