package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class RequestHistoryTest {
  static final Path DB;

  static {
    try {
      DB = Files.createTempFile("history-test", ".db");
      DB.toFile().deleteOnExit();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
  }

  @Autowired RequestHistory history;
  @Autowired FamilyStore store;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @MockitoBean Agent agent;

  JsonNode messages() {
    var m = json.createArrayNode();
    m.addObject().put("role", "user").put("content", "Add Alice");
    return m;
  }

  Plan plan() {
    return new Plan(List.of(new Plan.CreatePerson("@a", "Alice")));
  }

  @BeforeEach
  void clear() {
    db.update("DELETE FROM request_history");
    db.update("DELETE FROM parent_edge");
    db.update("DELETE FROM spouse_edge");
    db.update("DELETE FROM person");
  }

  @Test
  void completedRetryReplaysWithoutApplyingAgainAndRejectsDifferentContent() {
    when(agent.reply(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              Function<Plan, FamilyStore.Applied> apply = call.getArgument(2);
              apply.apply(plan());
              return "Saved Alice.";
            });
    assertEquals("Saved Alice.", history.reply("same", messages()));
    assertEquals("Saved Alice.", history.reply("same", messages()));
    assertEquals(1, store.graph().people().size());
    verify(agent, times(1)).reply(any(), anyString(), any());
    var changed = json.createArrayNode();
    changed.addObject().put("role", "user").put("content", "Different");
    assertEquals(
        "REQUEST_CONFLICT",
        assertThrows(Family.Invalid.class, () -> history.reply("same", changed)).code);
  }

  @Test
  void concurrentDuplicateDoesNotInvokeAgentTwice() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(agent.reply(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return "Done";
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> history.reply("same", messages()));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(
            "REQUEST_IN_PROGRESS",
            assertThrows(Family.Invalid.class, () -> history.reply("same", messages())).code);
      } finally {
        release.countDown();
      }
      assertEquals("Done", first.get(5, TimeUnit.SECONDS));
    }
    verify(agent, times(1)).reply(any(), anyString(), any());
  }

  @Test
  void crashAfterCommitRetainsAppliedFallbackAndCannotRepeatMutation() {
    when(agent.reply(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              Function<Plan, FamilyStore.Applied> apply = call.getArgument(2);
              apply.apply(plan());
              throw new RuntimeException("Simulated lost response");
            });
    assertThrows(RuntimeException.class, () -> history.reply("same", messages()));
    history.recover();
    assertTrue(history.reply("same", messages()).startsWith("Your changes were saved"));
    assertEquals(1, store.graph().people().size());
    verify(agent, times(1)).reply(any(), anyString(), any());
  }

  @Test
  void historyWriteFailureRollsBackGraphAndRestartAllowsUncommittedRetry() {
    when(agent.reply(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              Function<Plan, FamilyStore.Applied> apply = call.getArgument(2);
              apply.apply(plan());
              return "Saved";
            });
    db.execute(
        "CREATE TRIGGER fail_history BEFORE UPDATE OF status ON request_history WHEN NEW.status='applied' BEGIN SELECT RAISE(ABORT,'test'); END");
    try {
      assertThrows(RuntimeException.class, () -> history.reply("same", messages()));
    } finally {
      db.execute("DROP TRIGGER fail_history");
    }
    assertTrue(store.graph().people().isEmpty());
    db.update("UPDATE request_history SET status='processing' WHERE request_id='same'");
    history.recover();
    assertEquals("Saved", history.reply("same", messages()));
    assertEquals(1, store.graph().people().size());
  }

  @Test
  void clearingHistoryKeepsFamilyAndRejectsActiveRequests() {
    when(agent.reply(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              Function<Plan, FamilyStore.Applied> apply = call.getArgument(2);
              apply.apply(plan());
              return "Saved";
            });
    history.reply("clear-test", messages());
    db.update("UPDATE request_history SET status='processing' WHERE request_id='clear-test'");
    assertEquals("REQUEST_IN_PROGRESS", assertThrows(Family.Invalid.class, history::clear).code);
    db.update("UPDATE request_history SET status='completed' WHERE request_id='clear-test'");
    history.clear();
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM request_history", Integer.class));
    assertEquals(1, store.graph().people().size());
  }
}
