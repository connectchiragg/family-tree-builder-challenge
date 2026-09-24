package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;

class AgentTest {
  final ObjectMapper json = new ObjectMapper();
  final FamilyStore store = mock(FamilyStore.class);
  final Tools tools = new Tools(store, json);

  @BeforeEach
  void setup() {
    when(store.graph()).thenReturn(new Family.Graph(List.of(), List.of(), List.of()));
  }

  JsonNode parse(String text) throws Exception {
    return json.readTree(text);
  }

  JsonNode history() throws Exception {
    return parse("[{\"role\":\"user\",\"content\":\"Add Alice\"}]");
  }

  JsonNode call(String id, String name, String input) throws Exception {
    return parse(
        "{\"content\":[{\"type\":\"tool_use\",\"id\":\""
            + id
            + "\",\"name\":\""
            + name
            + "\",\"input\":"
            + input
            + "}],\"stop_reason\":\"tool_use\"}");
  }

  JsonNode text() throws Exception {
    return parse(
        "{\"content\":[{\"type\":\"text\",\"text\":\"Saved.\"},{\"type\":\"text\",\"text\":\"Done.\"}],\"stop_reason\":\"end_turn\"}");
  }

  @Test
  void runsToolsAndReturnsAllTextWithoutMutatingHistory() throws Exception {
    var responses =
        new ArrayDeque<JsonNode>(
            List.of(call("1", "create_person", "{\"name\":\"Alice\"}"), text()));
    when(store.create("Alice")).thenReturn(new Family.Person("a", "Alice"));
    var sent = new ArrayList<JsonNode>();
    ModelClient model =
        (messages, definitions, prompt) -> {
          sent.add(messages.deepCopy());
          return responses.removeFirst();
        };
    var history = history();
    assertEquals("Saved.\nDone.", new Agent(model, tools, store, json).reply(history));
    assertEquals(1, history.size());
    assertEquals("1", sent.get(1).get(2).path("content").get(0).path("tool_use_id").asText());
    verify(store).create("Alice");
  }

  @Test
  void repeatedToolIdReplaysResultWithoutRepeatingWrite() throws Exception {
    var call = call("1", "create_person", "{\"name\":\"Alice\"}");
    var responses = new ArrayDeque<>(List.of(call, call, text()));
    new Agent((m, t, s) -> responses.removeFirst(), tools, store, json).reply(history());
    verify(store, times(1)).create("Alice");
  }

  @Test
  void changedInputForRepeatedIdIsRejected() throws Exception {
    var responses =
        new ArrayDeque<>(
            List.of(
                call("1", "create_person", "{\"name\":\"Alice\"}"),
                call("1", "create_person", "{\"name\":\"Bob\"}")));
    assertThrows(
        HttpModelClient.Unavailable.class,
        () -> new Agent((m, t, s) -> responses.removeFirst(), tools, store, json).reply(history()));
    verify(store, never()).create("Bob");
  }

  @Test
  void validationErrorsAreFedBackToModel() throws Exception {
    var response = call("1", "create_person", "{\"name\":\"Alice\",\"extra\":true}");
    var done = text();
    ModelClient model =
        (m, t, s) -> {
          if (m.size() == 1) return response;
          assertTrue(m.get(2).path("content").get(0).path("is_error").asBoolean());
          return done;
        };
    new Agent(model, tools, store, json).reply(history());
    verify(store, never()).create(anyString());
  }

  @Test
  void boundedLoopAndTruncatedOutputFailExplicitly() throws Exception {
    var repeated = call("1", "get_family_tree", "{}");
    assertThrows(
        HttpModelClient.Unavailable.class,
        () -> new Agent((m, t, s) -> repeated, tools, store, json).reply(history()));
    var truncated = parse("{\"content\":[],\"stop_reason\":\"max_tokens\"}");
    assertThrows(
        HttpModelClient.Unavailable.class,
        () -> new Agent((m, t, s) -> truncated, tools, store, json).reply(history()));
  }

  @Test
  void clarificationCanReturnWithoutWrites() throws Exception {
    var response =
        parse("{\"content\":[{\"type\":\"text\",\"text\":\"Which John do you mean?\"}]}");
    assertEquals(
        "Which John do you mean?",
        new Agent((m, t, s) -> response, tools, store, json).reply(history()));
    verify(store, never()).create(anyString());
  }

  @Test
  void toolValidationAndDomainErrorsAreStructured() throws Exception {
    assertTrue(tools.execute("unknown", parse("{}")).error());
    assertTrue(tools.execute("create_person", parse("{\"name\":42}")).error());
    when(store.create("Alice")).thenThrow(new Family.Invalid("INVALID_INPUT", "Rejected"));
    var result = tools.execute("create_person", parse("{\"name\":\"Alice\"}"));
    assertTrue(result.error());
    assertEquals("INVALID_INPUT", json.valueToTree(result.value()).path("code").asText());
  }
}
