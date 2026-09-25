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
  @Autowired ModelClient modelClient;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

  @Test
  void selfIntroductionResponseSavesOnePerson() throws Exception {
    var response =
        modelClient.parse(
            json.readTree(
                """
        {"message":"","answerQuestion":"","operations":[
          {"type":"create_person","ref":"@chirag","name":"Chirag"}
        ]}
        """));
    var saved = store.apply(new Plan(response.operations()));
    assertEquals(1, saved.graph().people().size());
    assertEquals("Chirag", store.graph().people().getFirst().name());
    assertTrue(store.graph().parentEdges().isEmpty());
    assertTrue(store.graph().spouseEdges().isEmpty());
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate db;

  @BeforeEach
  void reset() {
    db.update("DELETE FROM spouse_edge");
    db.update("DELETE FROM parent_edge");
    db.update("DELETE FROM person");
  }

  Relationship parent(Person a, Person b) {
    return new Relationship(Family.RelationshipKind.PARENT, a.id(), b.id());
  }

  // Fixtures use the same batch entry point as the application.
  Person create(String name) {
    var result = store.apply(new Plan(java.util.List.of(new Plan.CreatePerson("@new", name))));
    return result.graph().people().stream()
        .filter(p -> p.id().equals(result.createdIds().get("@new")))
        .findFirst()
        .orElseThrow();
  }

  java.util.List<Person> find(String name) {
    return store.graph().people().stream().filter(p -> p.name().equalsIgnoreCase(name)).toList();
  }

  void add(Relationship edge) {
    store.apply(
        new Plan(
            java.util.List.of(new Plan.AddRelationship(edge.kind(), edge.fromId(), edge.toId()))));
  }

  void rename(String id, String name) {
    store.apply(new Plan(java.util.List.of(new Plan.RenamePerson(id, name))));
  }

  void replace(Relationship oldEdge, Relationship newEdge) {
    store.apply(
        new Plan(
            java.util.List.of(
                new Plan.ReplaceRelationship(
                    oldEdge.kind(),
                    oldEdge.fromId(),
                    oldEdge.toId(),
                    newEdge.kind(),
                    newEdge.fromId(),
                    newEdge.toId()))));
  }

  @Test
  void emptyGraphAndDuplicateNamesWithStableRename() {
    assertTrue(store.graph().people().isEmpty());
    var first = create("John");
    var second = create("John");
    assertNotEquals(first.id(), second.id());
    assertEquals(2, find("JOHN").size());
    add(parent(first, second));
    rename(first.id(), "Jon");
    assertEquals(first.id(), find("Jon").getFirst().id());
    assertEquals(1, store.graph().parentEdges().size());
  }

  @Test
  void rejectsCyclesSelfLinksMissingPeopleAndThirdParent() {
    var a = create("A");
    var b = create("B");
    var c = create("C");
    var d = create("D");
    add(parent(a, b));
    add(parent(b, c));
    assertEquals(
        "CYCLE_DETECTED",
        assertThrows(InvalidFamilyOperationException.class, () -> add(parent(c, a))).code);
    assertEquals(
        "SELF_RELATIONSHIP",
        assertThrows(InvalidFamilyOperationException.class, () -> add(parent(a, a))).code);
    assertEquals(
        "PERSON_NOT_FOUND",
        assertThrows(
                InvalidFamilyOperationException.class,
                () -> add(new Relationship(Family.RelationshipKind.PARENT, "missing", a.id())))
            .code);
    add(parent(a, c));
    add(parent(a, c));
    assertEquals(
        "PARENT_LIMIT",
        assertThrows(InvalidFamilyOperationException.class, () -> add(parent(d, c))).code);
    assertEquals(3, store.graph().parentEdges().size());
  }

  @Test
  void spouseIsUndirectedAndDoesNotImplyParenthood() {
    var a = create("A");
    var b = create("B");
    var c = create("C");
    add(new Relationship(Family.RelationshipKind.SPOUSE, a.id(), b.id()));
    add(new Relationship(Family.RelationshipKind.SPOUSE, b.id(), a.id()));
    assertEquals(1, store.graph().spouseEdges().size());
    assertTrue(store.graph().parentEdges().isEmpty());
    assertThrows(
        InvalidFamilyOperationException.class,
        () -> add(new Relationship(Family.RelationshipKind.SPOUSE, a.id(), c.id())));
  }

  @Test
  void correctionCommitsOrRollsBackAsOneUnit() {
    var a = create("A");
    var b = create("B");
    var c = create("C");
    add(parent(a, b));
    replace(parent(a, b), parent(c, b));
    assertEquals(java.util.List.of(new ParentEdge(c.id(), b.id())), store.graph().parentEdges());
    var before = store.graph();
    assertThrows(InvalidFamilyOperationException.class, () -> replace(parent(c, b), parent(a, a)));
    assertEquals(before, store.graph());
    assertThrows(InvalidFamilyOperationException.class, () -> replace(parent(a, b), parent(a, c)));
    assertEquals(before, store.graph());
  }

  @Test
  void committedDataSurvivesNewConnection() throws Exception {
    create("Persistent");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DB);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT name FROM person")) {
      assertTrue(rows.next());
      assertEquals("Persistent", rows.getString(1));
    }
  }

  @Test
  void concurrentParentWritesCannotExceedLimit() throws Exception {
    var child = create("Child");
    var parents = java.util.List.of(create("A"), create("B"), create("C"));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          parents.stream()
              .map(
                  p ->
                      executor.submit(
                          () -> {
                            try {
                              add(parent(p, child));
                              return true;
                            } catch (InvalidFamilyOperationException e) {
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
                    new Plan.AddRelationship(Family.RelationshipKind.PARENT, "@a", "@b"))));
    assertEquals(2, result.createdIds().size());
    assertEquals(2, find("John").size());
    assertEquals(1, result.graph().parentEdges().size());
  }

  @Test
  void invalidLaterOperationPerformsNoSqlWrites() {
    org.mockito.Mockito.clearInvocations(db);
    assertThrows(
        InvalidFamilyOperationException.class,
        () ->
            store.apply(
                new Plan(
                    java.util.List.of(
                        new Plan.CreatePerson("@a", "Alice"),
                        new Plan.AddRelationship(
                            Family.RelationshipKind.PARENT, "@a", "missing")))));
    assertTrue(
        org.mockito.Mockito.mockingDetails(db).getInvocations().stream()
            .noneMatch(i -> i.getMethod().getName().equals("update")));
    assertTrue(store.graph().people().isEmpty());
  }

  @Test
  void batchRejectsUndeclaredReferencesAndCycles() {
    var empty = store.graph();
    assertThrows(
        InvalidFamilyOperationException.class,
        () ->
            store.apply(
                new Plan(
                    java.util.List.of(
                        new Plan.AddRelationship(Family.RelationshipKind.PARENT, "@a", "@b")))));
    assertThrows(
        InvalidFamilyOperationException.class,
        () ->
            store.apply(
                new Plan(
                    java.util.List.of(
                        new Plan.CreatePerson("@a", "A"),
                        new Plan.CreatePerson("@b", "B"),
                        new Plan.AddRelationship(Family.RelationshipKind.PARENT, "@a", "@b"),
                        new Plan.AddRelationship(Family.RelationshipKind.PARENT, "@b", "@a")))));
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
    var person = create("Initial");
    rename(person.id(), "Earlier edit");
    store.apply(new Plan(java.util.List.of(new Plan.RenamePerson(person.id(), "Latest edit"))));
    assertEquals(person.id(), find("Latest edit").getFirst().id());
  }

  @Test
  void deletePersonRemovesIncidentEdgesButKeepsNamesakesAndOtherPeople() {
    var john = create("John");
    var otherJohn = create("John");
    var parent = create("Parent");
    var spouse = create("Spouse");
    var child = create("Child");
    add(parent(parent, john));
    add(parent(john, child));
    add(new Relationship(Family.RelationshipKind.SPOUSE, john.id(), spouse.id()));
    add(parent(parent, otherJohn));
    store.apply(new Plan(java.util.List.of(new Plan.DeletePerson(john.id()))));
    assertEquals(java.util.List.of(otherJohn), find("John"));
    assertEquals(4, store.graph().people().size());
    assertEquals(
        java.util.List.of(new ParentEdge(parent.id(), otherJohn.id())),
        store.graph().parentEdges());
    assertTrue(store.graph().spouseEdges().isEmpty());
  }

  @Test
  void failedBatchAfterDeletionLeavesOriginalPersonAndEdgesUntouched() {
    var a = create("A");
    var b = create("B");
    add(parent(a, b));
    var before = store.graph();
    org.mockito.Mockito.clearInvocations(db);
    assertThrows(
        InvalidFamilyOperationException.class,
        () ->
            store.apply(
                new Plan(
                    java.util.List.of(
                        new Plan.DeletePerson(a.id()),
                        new Plan.RenamePerson(a.id(), "Cannot rename deleted person")))));
    assertEquals(before, store.graph());
    assertTrue(
        org.mockito.Mockito.mockingDetails(db).getInvocations().stream()
            .noneMatch(i -> i.getMethod().getName().equals("update")));
    assertEquals(
        "PERSON_NOT_FOUND",
        assertThrows(
                InvalidFamilyOperationException.class,
                () -> store.apply(new Plan(java.util.List.of(new Plan.DeletePerson("missing")))))
            .code);
  }
}
