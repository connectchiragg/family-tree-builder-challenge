package pro.workhero.family;

import static pro.workhero.family.Family.*;
import static pro.workhero.family.InvalidFamilyOperationException.require;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class Api {
  private final RequestHistory history;
  private final FamilyStore store;

  public Api(RequestHistory history, FamilyStore store) {
    this.history = history;
    this.store = store;
  }

  @GetMapping("/graph")
  public Graph graph() {
    return store.graph();
  }

  @GetMapping("/health")
  public Map<String, Boolean> health() {
    store.graph();
    return Map.of("ok", true);
  }

  @DeleteMapping("/history")
  public Map<String, Boolean> clearHistory() {
    history.clear();
    return Map.of("ok", true);
  }

  @PostMapping("/chat")
  public Map<String, String> chat(@RequestBody JsonNode body) {
    var messages = body.path("messages");
    require(
        messages.isArray() && !messages.isEmpty() && messages.size() <= 100,
        "INVALID_INPUT",
        "Provide 1–100 chat messages.");
    int length = 0;
    for (var message : messages) {
      var role = message.path("role").asText();
      var content = message.path("content");
      require(
          role.equals("user") || role.equals("assistant"),
          "INVALID_INPUT",
          "Only user and assistant roles are accepted.");
      require(
          content.isTextual() && !content.asText().isBlank() && content.asText().length() <= 8000,
          "INVALID_INPUT",
          "Each message must contain 1–8000 characters.");
      length += content.asText().length();
      require(message.size() == 2, "INVALID_INPUT", "Messages accept only role and content.");
    }
    require(
        length <= 32000 && messages.get(messages.size() - 1).path("role").asText().equals("user"),
        "INVALID_INPUT",
        "End with a user message; conversation limit is 32000 characters.");
    return Map.of("reply", history.reply(body.path("requestId").asText(), messages));
  }

  @ExceptionHandler(InvalidFamilyOperationException.class)
  ResponseEntity<?> invalid(InvalidFamilyOperationException e) {
    return ResponseEntity.status(e.code.startsWith("REQUEST_") ? 409 : 400)
        .body(Map.of("error", e.getMessage(), "code", e.code));
  }

  @ExceptionHandler(HttpModelClient.Unavailable.class)
  ResponseEntity<?> unavailable(HttpModelClient.Unavailable e) {
    return ResponseEntity.status(502)
        .body(Map.of("error", e.getMessage(), "code", "MODEL_UNAVAILABLE"));
  }
}
