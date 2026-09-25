package pro.workhero.family;

import com.fasterxml.jackson.databind.JsonNode;

/** Provider boundary: callers declare the validated Java response type they expect. */
public interface ModelClient {
  <T> T complete(JsonNode messages, String system, Class<T> responseType);
}
