package pro.workhero.family;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class HttpModelClient implements ModelClient {
  private final ObjectMapper json;
  private final String key, model, base;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public HttpModelClient(ObjectMapper json, Environment env) {
    this.json = json;
    key = env.getProperty("ANTHROPIC_API_KEY", "");
    model = env.getProperty("ANTHROPIC_MODEL", "anthropic/claude-sonnet-4");
    base = env.getProperty("ANTHROPIC_BASE_URL", "https://openrouter.ai/api").replaceAll("/+$", "");
  }

  @Override
  public JsonNode complete(JsonNode messages, JsonNode tools, String system) {
    if (key.isBlank())
      throw new Unavailable("Set ANTHROPIC_API_KEY in server/.env to enable chat.");
    try {
      var body =
          json.writeValueAsString(
              Map.of(
                  "model",
                  model,
                  "max_tokens",
                  2048,
                  "system",
                  system,
                  "tools",
                  tools,
                  "messages",
                  messages));
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
      return json.readTree(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Unavailable("Model request interrupted.");
    } catch (java.io.IOException e) {
      throw new Unavailable("Model provider unavailable or timed out.");
    }
  }

  public static class Unavailable extends RuntimeException {
    public Unavailable(String message) {
      super(message);
    }
  }
}
