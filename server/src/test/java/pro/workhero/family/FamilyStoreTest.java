package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;
import static pro.workhero.family.Family.*;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class FamilyStoreTest {
  static final Path DB;

  static {
    try {
      DB = Files.createTempFile("family-test-", ".db");
      DB.toFile().deleteOnExit();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
  }

  @Autowired FamilyStore store;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate db;

  @BeforeEach
  void reset() {
    db.update("DELETE FROM spouse_edge");
    db.update("DELETE FROM parent_edge");
    db.update("DELETE FROM person");
  }

  Relationship parent(Person a, Person b) {
    return new Relationship("parent", a.id(), b.id());
  }

  @Test
  void emptyGraphAndDuplicateNamesWithStableRename() {
    assertTrue(store.graph().people().isEmpty());
    var first = store.create("John");
    var second = store.create("John");
    assertNotEquals(first.id(), second.id());
    assertEquals(2, store.find("JOHN").size());
    store.add(parent(first, second));
    store.rename(first.id(), "Jon");
    assertEquals(first.id(), store.find("Jon").getFirst().id());
    assertEquals(1, store.graph().parentEdges().size());
  }

  @Test
  void rejectsCyclesSelfLinksMissingPeopleAndThirdParent() {
    var a = store.create("A");
    var b = store.create("B");
    var c = store.create("C");
    var d = store.create("D");
    store.add(parent(a, b));
    store.add(parent(b, c));
    assertEquals("CYCLE_DETECTED", assertThrows(Invalid.class, () -> store.add(parent(c, a))).code);
    assertEquals(
        "SELF_RELATIONSHIP", assertThrows(Invalid.class, () -> store.add(parent(a, a))).code);
    assertEquals(
        "PERSON_NOT_FOUND",
        assertThrows(Invalid.class, () -> store.add(new Relationship("parent", "missing", a.id())))
            .code);
    store.add(parent(a, c));
    store.add(parent(a, c));
    assertEquals("PARENT_LIMIT", assertThrows(Invalid.class, () -> store.add(parent(d, c))).code);
    assertEquals(3, store.graph().parentEdges().size());
  }

  @Test
  void spouseIsUndirectedAndDoesNotImplyParenthood() {
    var a = store.create("A");
    var b = store.create("B");
    var c = store.create("C");
    store.add(new Relationship("spouse", a.id(), b.id()));
    store.add(new Relationship("spouse", b.id(), a.id()));
    assertEquals(1, store.graph().spouseEdges().size());
    assertTrue(store.graph().parentEdges().isEmpty());
    assertThrows(Invalid.class, () -> store.add(new Relationship("spouse", a.id(), c.id())));
  }

  @Test
  void correctionCommitsOrRollsBackAsOneUnit() {
    var a = store.create("A");
    var b = store.create("B");
    var c = store.create("C");
    store.add(parent(a, b));
    store.replace(parent(a, b), parent(c, b));
    assertEquals(java.util.List.of(new ParentEdge(c.id(), b.id())), store.graph().parentEdges());
    var before = store.graph();
    assertThrows(Invalid.class, () -> store.replace(parent(c, b), parent(a, a)));
    assertEquals(before, store.graph());
    assertThrows(Invalid.class, () -> store.replace(parent(a, b), parent(a, c)));
    assertEquals(before, store.graph());
  }

  @Test
  void committedDataSurvivesNewConnection() throws Exception {
    store.create("Persistent");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DB);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT name FROM person")) {
      assertTrue(rows.next());
      assertEquals("Persistent", rows.getString(1));
    }
  }

  @Test
  void concurrentParentWritesCannotExceedLimit() throws Exception {
    var child = store.create("Child");
    var parents = java.util.List.of(store.create("A"), store.create("B"), store.create("C"));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          parents.stream()
              .map(
                  p ->
                      executor.submit(
                          () -> {
                            try {
                              store.add(parent(p, child));
                              return true;
                            } catch (Invalid e) {
                              return false;
                            }
                          }))
              .toList();
      int accepted = 0;
      for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) accepted++;
      assertEquals(2, accepted);
      assertEquals(2, store.graph().parentEdges().size());
    }
  }

  @Test
  void batchResolvesNewPeopleAndAllowsDuplicateNames() {
    var result =
        store.apply(
            new Plan(
                java.util.List.of(
                    new Plan.CreatePerson("@a", "John"),
                    new Plan.CreatePerson("@b", "John"),
                    new Plan.AddRelationship("parent", "@a", "@b"))));
    assertEquals(2, result.createdIds().size());
    assertEquals(2, store.find("John").size());
    assertEquals(1, result.graph().parentEdges().size());
  }

  @Test
  void invalidLaterOperationPerformsNoSqlWrites() {
    org.mockito.Mockito.clearInvocations(db);
    assertThrows(
        Invalid.class,
        () ->
            store.apply(
                new Plan(
                    java.util.List.of(
                        new Plan.CreatePerson("@a", "Alice"),
                        new Plan.AddRelationship("parent", "@a", "missing")))));
    assertTrue(
        org.mockito.Mockito.mockingDetails(db).getInvocations().stream()
            .noneMatch(i -> i.getMethod().getName().equals("update")));
    assertTrue(store.graph().people().isEmpty());
  }

  @Test
  void batchRejectsUndeclaredReferencesAndCycles() {
    var empty = store.graph();
    assertThrows(
        Invalid.class,
        () ->
            store.apply(
                new Plan(java.util.List.of(new Plan.AddRelationship("parent", "@a", "@b")))));
    assertThrows(
        Invalid.class,
        () ->
            store.apply(
                new Plan(
                    java.util.List.of(
                        new Plan.CreatePerson("@a", "A"),
                        new Plan.CreatePerson("@b", "B"),
                        new Plan.AddRelationship("parent", "@a", "@b"),
                        new Plan.AddRelationship("parent", "@b", "@a")))));
    assertEquals(empty, store.graph());
  }

  @Test
  void databaseFailureDuringPersistenceRollsBackAllWrites() {
    db.execute(
        "CREATE TRIGGER fail_insert BEFORE INSERT ON person WHEN NEW.name='Fail' BEGIN SELECT RAISE(ABORT,'test failure'); END");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              store.apply(
                  new Plan(
                      java.util.List.of(
                          new Plan.CreatePerson("@a", "Alice"),
                          new Plan.CreatePerson("@b", "Fail")))));
      assertTrue(store.graph().people().isEmpty());
    } finally {
      db.execute("DROP TRIGGER fail_insert");
    }
  }

  @Test
  void laterRenameWinsWithoutSnapshotRejection() {
    var person = store.create("Initial");
    store.rename(person.id(), "Earlier edit");
    store.apply(new Plan(java.util.List.of(new Plan.RenamePerson(person.id(), "Latest edit"))));
    assertEquals(person.id(), store.find("Latest edit").getFirst().id());
  }
}
