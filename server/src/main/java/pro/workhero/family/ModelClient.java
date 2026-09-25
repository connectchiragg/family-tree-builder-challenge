package pro.workhero.family;

import com.fasterxml.jackson.databind.JsonNode;

/** The only provider boundary; tests supply scripted responses. */
@FunctionalInterface
public interface ModelClient {
  JsonNode complete(JsonNode messages, JsonNode tools, String system);

  default JsonNode summarize(JsonNode messages, JsonNode tools, String system) {
    return complete(messages, tools, system);
  }
}
