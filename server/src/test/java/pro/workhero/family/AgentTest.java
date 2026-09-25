package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;

class AgentTest {
  final ObjectMapper json = new ObjectMapper();
  final FamilyStore store = mock(FamilyStore.class);
  final ModelClient model = mock(ModelClient.class);
  final Family.Graph graph = new Family.Graph(List.of(), List.of(), List.of());
  Tools tools = new Tools(store, json);

  @BeforeEach
  void setup() {
    when(store.graph()).thenReturn(graph);
  }

  JsonNode history() throws Exception {
    return json.readTree("[{\"role\":\"user\",\"content\":\"Add Alice\"}]");
  }

  JsonNode text(String value) {
    return json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", value))));
  }

  JsonNode plan() throws Exception {
    return json.readTree(
        """
    {"content":[{"type":"tool_use","id":"call1","name":"apply_family_changes","input":{"operations":[{"type":"create_person","ref":"@a","name":"Alice"}]}}]}
    """);
  }

  Agent agent() {
    return new Agent(model, tools, store, json);
  }

  JsonNode questionPlan() throws Exception {
    var result = plan();
    ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("content").get(0).path("input"))
        .put("answerQuestion", "Who are Alice's parents?");
    return result;
  }

  @Test
  void plainMutationUsesOneCallAndPreservesHistory() throws Exception {
    when(model.complete(any(), any(), anyString())).thenReturn(plan());
    when(store.apply(any())).thenReturn(new FamilyStore.Applied(Map.of("@a", "a"), graph));
    var history = history();
    assertEquals("Saved 1 requested change(s).", agent().reply(history));
    assertEquals(1, history.size());
    verify(model, never()).summarize(any(), any(), anyString());
    verify(store).apply(any());
  }

  @Test
  void mutationWithQuestionSendsOnlyQuestionAndResultToSecondCall() throws Exception {
    when(model.complete(any(), any(), anyString())).thenReturn(questionPlan());
    when(store.apply(any())).thenReturn(new FamilyStore.Applied(Map.of(), graph));
    when(model.summarize(any(), any(), anyString()))
        .thenAnswer(
            call -> {
              JsonNode messages = call.getArgument(0), definitions = call.getArgument(1);
              assertEquals(1, messages.size());
              assertTrue(definitions.isEmpty());
              assertTrue(
                  messages.get(0).path("content").asText().contains("Who are Alice's parents?"));
              assertFalse(messages.toString().contains("Add Alice"));
              return text("No parents recorded.");
            });
    assertEquals("Saved 1 requested change(s).\nNo parents recorded.", agent().reply(history()));
    verify(model).summarize(any(), any(), anyString());
    verify(store).apply(any());
  }

  @Test
  void clarificationNeedsOneCallAndNoPlanExecution() throws Exception {
    when(model.complete(any(), any(), anyString())).thenReturn(text("Which John do you mean?"));
    assertEquals("Which John do you mean?", agent().reply(history()));
    verify(model, never()).summarize(any(), any(), anyString());
    verify(store, never()).apply(any());
  }

  @Test
  void invalidPlanIsExplainedWithoutRepairOrRetry() throws Exception {
    when(model.complete(any(), any(), anyString())).thenReturn(plan());
    when(store.apply(any())).thenThrow(new Family.Invalid("PERSON_NOT_FOUND", "Unknown person."));
    assertEquals("Nothing was saved. Unknown person.", agent().reply(history()));
    verify(model, never()).summarize(any(), any(), anyString());
    verify(store, times(1)).apply(any());
    verify(model, times(1)).complete(any(), any(), anyString());
  }

  @Test
  void secondCallCannotExecuteTools() throws Exception {
    when(model.complete(any(), any(), anyString())).thenReturn(questionPlan());
    when(store.apply(any())).thenReturn(new FamilyStore.Applied(Map.of(), graph));
    when(model.summarize(any(), any(), anyString())).thenReturn(plan());
    assertTrue(agent().reply(history()).startsWith("Saved 1 requested change(s)."));
    verify(store, times(1)).apply(any());
  }

  @Test
  void multiplePlansAreRejectedBeforeAnyExecution() throws Exception {
    var response = plan();
    ((com.fasterxml.jackson.databind.node.ArrayNode) response.path("content"))
        .add(response.path("content").get(0).deepCopy().deepCopy());
    ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("content").get(1))
        .put("id", "call2");
    when(model.complete(any(), any(), anyString())).thenReturn(response);

    agent().reply(history());
    verify(store, never()).apply(any());
  }

  @Test
  void strictTypedPlanRejectsWrongTypesAndExtraFields() throws Exception {
    for (var input :
        List.of(
            "{\"operations\":[{\"type\":\"create_person\",\"ref\":\"@a\",\"name\":42}]}",
            "{\"operations\":[],\"extra\":true}",
            "{\"operations\":[],\"answerQuestion\":42}",
            "{\"operations\":[{\"type\":\"create_person\",\"ref\":\"@a\"}]}"))
      assertTrue(tools.execute("apply_family_changes", json.readTree(input)).error());
    verify(store, never()).apply(any());
  }

  @Test
  void truncatedPlanDoesNotExecute() throws Exception {
    var response = (com.fasterxml.jackson.databind.node.ObjectNode) plan();
    response.put("stop_reason", "max_tokens");
    when(model.complete(any(), any(), anyString())).thenReturn(response);
    assertThrows(HttpModelClient.Unavailable.class, () -> agent().reply(history()));
    verify(store, never()).apply(any());
  }
}
