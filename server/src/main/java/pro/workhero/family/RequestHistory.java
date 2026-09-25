package pro.workhero.family;

import static pro.workhero.family.Family.*;
import static pro.workhero.family.InvalidFamilyOperationException.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** One backend process owns this database. No raw conversation or credentials are stored. */
@Service
public class RequestHistory {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Agent agent;
  private final FamilyStore store;
  private final ObjectMapper json;

  public RequestHistory(
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      Agent agent,
      FamilyStore store,
      ObjectMapper json) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.agent = agent;
    this.store = store;
    this.json = json;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    db.update(
        "UPDATE request_history SET status='completed', updated_at=? WHERE status='applied'",
        System.currentTimeMillis());
    db.update(
        "UPDATE request_history SET status='failed', error_code='INTERRUPTED', updated_at=? WHERE status='processing'",
        System.currentTimeMillis());
  }

  public String reply(String id, JsonNode messages) {
    require(
        id != null && id.matches("[a-zA-Z0-9-]{1,80}"),
        "INVALID_INPUT",
        "A requestId is required.");
    var hash = hash(messages);
    var cached =
        tx.execute(
            status -> {
              long now = System.currentTimeMillis();
              int inserted =
                  db.update(
                      "INSERT OR IGNORE INTO request_history(request_id,payload_hash,status,created_at,updated_at) VALUES (?,?,'processing',?,?)",
                      id,
                      hash,
                      now,
                      now);
              if (inserted == 1) return null;
              var row = db.queryForMap("SELECT * FROM request_history WHERE request_id=?", id);
              require(
                  hash.equals(row.get("payload_hash")),
                  "REQUEST_CONFLICT",
                  "This request ID was already used for different content.");
              var state = row.get("status");
              require(
                  !"processing".equals(state),
                  "REQUEST_IN_PROGRESS",
                  "This request is still running. Retry shortly with the same request ID.");
              if ("completed".equals(state) || "applied".equals(state))
                return (String) row.get("reply");
              db.update(
                  "UPDATE request_history SET status='processing', error_code=NULL, updated_at=? WHERE request_id=?",
                  now,
                  id);
              return null;
            });
    if (cached != null) return cached;
    long started = System.currentTimeMillis();
    try {
      var reply = agent.reply(messages, id, plan -> apply(id, plan));
      db.update(
          "UPDATE request_history SET status='completed', reply=?, duration_ms=?, updated_at=? WHERE request_id=?",
          reply,
          System.currentTimeMillis() - started,
          System.currentTimeMillis(),
          id);
      return reply;
    } catch (RuntimeException error) {
      // An applied request retains its durable fallback, even if communication fails.
      db.update(
          "UPDATE request_history SET status='failed', error_code=?, duration_ms=?, updated_at=? WHERE request_id=? AND status='processing'",
          error instanceof InvalidFamilyOperationException invalid
              ? invalid.code
              : "REQUEST_FAILED",
          System.currentTimeMillis() - started,
          System.currentTimeMillis(),
          id);
      throw error;
    }
  }

  FamilyStore.Applied apply(String id, Plan plan) {
    return tx.execute(
        status -> {
          var result = store.apply(plan);
          int updated =
              db.update(
                  "UPDATE request_history SET status='applied', reply=?, updated_at=? WHERE request_id=? AND status='processing'",
                  "Your changes were saved. The family view shows the saved result.",
                  System.currentTimeMillis(),
                  id);
          require(updated == 1, "REQUEST_CONFLICT", "The request is no longer active.");
          return result;
        });
  }

  public void clear() {
    tx.executeWithoutResult(
        status -> {
          require(
              db.queryForObject(
                      "SELECT COUNT(*) FROM request_history WHERE status IN ('processing','applied')",
                      Integer.class)
                  == 0,
              "REQUEST_IN_PROGRESS",
              "Wait for the active request to finish before clearing conversation.");
          db.update("DELETE FROM request_history");
        });
  }

  private String hash(JsonNode messages) {
    var canonical = json.createArrayNode();
    messages.forEach(
        m ->
            canonical
                .addObject()
                .put("role", m.path("role").asText())
                .put("content", m.path("content").asText()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
