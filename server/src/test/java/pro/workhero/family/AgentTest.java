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

  @BeforeEach
  void setup() {
    when(store.graph()).thenReturn(graph);
  }

  JsonNode history() throws Exception {
    return json.readTree("[{\"role\":\"user\",\"content\":\"Add Alice\"}]");
  }

  ModelResponse plan(String question) {
    return new ModelResponse(null, List.of(new Plan.CreatePerson("@a", "Alice")), question);
  }

  Agent agent() {
    return new Agent(model, store, json);
  }

  @Test
  void plainMutationUsesOneCallAndPreservesHistory() throws Exception {
    when(model.plan(any(), anyString())).thenReturn(plan(null));
    when(store.apply(any())).thenReturn(new FamilyStore.Applied(Map.of("@a", "a"), graph));
    var history = history();
    assertEquals("Saved 1 requested change(s).", agent().reply(history, "test", store::apply));
    assertEquals(1, history.size());
    verify(model, times(1)).plan(eq(history), anyString());
    verifyNoMoreInteractions(model);
    verify(store).apply(any());
  }

  @Test
  void mutationWithQuestionSendsOnlyQuestionAndSavedGraphToSecondCall() throws Exception {
    when(model.plan(any(), anyString())).thenReturn(plan("Who are Alice's parents?"));
    when(store.apply(any())).thenReturn(new FamilyStore.Applied(Map.of(), graph));
    when(model.answer(any(), anyString()))
        .thenAnswer(
            call -> {
              JsonNode messages = call.getArgument(0);
              assertEquals(1, messages.size());
              assertTrue(
                  messages.get(0).path("content").asText().contains("Who are Alice's parents?"));
              assertFalse(messages.toString().contains("Add Alice"));
              return "No parents recorded.";
            });
    assertEquals(
        "Saved 1 requested change(s).\nNo parents recorded.",
        agent().reply(history(), "test", store::apply));
    verify(model).answer(any(), anyString());
    verify(store).apply(any());
  }

  @Test
  void clarificationNeedsOneCallAndNoExecution() throws Exception {
    when(model.plan(any(), anyString()))
        .thenReturn(new ModelResponse("Which John do you mean?", List.of(), null));
    assertEquals("Which John do you mean?", agent().reply(history(), "test", store::apply));
    verify(store, never()).apply(any());
    verify(model, times(1)).plan(any(), anyString());
    verifyNoMoreInteractions(model);
  }

  @Test
  void invalidPlanIsExplainedWithoutRepairOrRetry() throws Exception {
    when(model.plan(any(), anyString())).thenReturn(plan(null));
    when(store.apply(any()))
        .thenThrow(new InvalidFamilyOperationException("PERSON_NOT_FOUND", "Unknown person."));
    assertEquals(
        "Nothing was saved. Unknown person.", agent().reply(history(), "test", store::apply));
    verify(store, times(1)).apply(any());
    verify(model, times(1)).plan(any(), anyString());
    verifyNoMoreInteractions(model);
  }

  @Test
  void failedAnswerStillConfirmsSaveAndDoesNotRetry() throws Exception {
    when(model.plan(any(), anyString())).thenReturn(plan("Who are Alice's parents?"));
    when(store.apply(any())).thenReturn(new FamilyStore.Applied(Map.of(), graph));
    when(model.answer(any(), anyString())).thenThrow(new ModelClient.Unavailable("Invalid answer"));
    assertEquals(
        "Saved 1 requested change(s). I couldn't generate the answer; your changes are saved in the family view.",
        agent().reply(history(), "test", store::apply));
    verify(store, times(1)).apply(any());
    verify(model).plan(any(), anyString());
    verify(model).answer(any(), anyString());
    verifyNoMoreInteractions(model);
  }

  @Test
  void malformedProviderResponseDoesNotReachPersistence() throws Exception {
    when(model.plan(any(), anyString()))
        .thenThrow(new ModelClient.Unavailable("Malformed response"));
    assertThrows(
        ModelClient.Unavailable.class, () -> agent().reply(history(), "test", store::apply));
    verify(store, never()).apply(any());
  }
}
