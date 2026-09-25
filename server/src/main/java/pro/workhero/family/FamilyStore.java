package pro.workhero.family;

import static pro.workhero.family.Family.*;
import static pro.workhero.family.InvalidFamilyOperationException.require;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class FamilyStore {
  private final JdbcTemplate db;

  public FamilyStore(JdbcTemplate db) {
    this.db = db;
  }

  public Graph graph() {
    return new Graph(
        db.query(
            "SELECT * FROM person ORDER BY name, id",
            (row, rowNumber) -> new Person(row.getString("id"), row.getString("name"))),
        db.query(
            "SELECT * FROM parent_edge ORDER BY parent_id, child_id",
            (row, rowNumber) -> new ParentEdge(row.getString(1), row.getString(2))),
        db.query(
            "SELECT * FROM spouse_edge ORDER BY person_a_id, person_b_id",
            (row, rowNumber) -> new SpouseEdge(row.getString(1), row.getString(2))));
  }

  /** Saved graph and the real IDs assigned to this batch's new-person references. */
  public record Applied(Map<String, String> createdIds, Graph graph) {}

  public Applied apply(Plan plan) {
    var before = graph();
    require(
        plan != null
            && plan.operations() != null
            && !plan.operations().isEmpty()
            && plan.operations().size() <= 40,
        "INVALID_INPUT",
        "A plan must contain 1–40 operations.");
    var draft = new GraphDraft(before);
    // @references connect new people within this batch before their database IDs are known.
    var createdIds = new LinkedHashMap<String, String>();
    for (var operation : plan.operations()) {
      require(operation != null, "INVALID_INPUT", "An operation cannot be null.");
      switch (operation) {
        case Plan.CreatePerson create -> {
          require(
              create.ref() != null
                  && create.ref().matches("@[A-Za-z][A-Za-z0-9_-]{0,39}")
                  && !createdIds.containsKey(create.ref()),
              "INVALID_REFERENCE",
              "Each new person needs a unique @reference.");
          createdIds.put(create.ref(), draft.create(create.name()).id());
        }
        case Plan.DeletePerson delete ->
            draft.delete(resolvePersonId(delete.person(), createdIds, draft));
        case Plan.RenamePerson change ->
            draft.rename(resolvePersonId(change.person(), createdIds, draft), change.name());
        case Plan.AddRelationship add ->
            draft.add(resolveRelationship(add.kind(), add.from(), add.to(), createdIds, draft));
        case Plan.RemoveRelationship change ->
            draft.remove(
                resolveRelationship(change.kind(), change.from(), change.to(), createdIds, draft));
        case Plan.ReplaceRelationship change ->
            draft.replace(
                resolveRelationship(
                    change.oldKind(), change.oldFrom(), change.oldTo(), createdIds, draft),
                resolveRelationship(
                    change.newKind(), change.newFrom(), change.newTo(), createdIds, draft));
      }
    }
    // Every operation has passed on the draft. Only now do writes begin.
    persist(before, draft.graph());
    return new Applied(Map.copyOf(createdIds), graph());
  }

  private String resolvePersonId(String ref, Map<String, String> createdIds, GraphDraft draft) {
    require(ref != null && !ref.isBlank(), "INVALID_REFERENCE", "A person reference is required.");
    var id = ref.startsWith("@") ? createdIds.get(ref) : ref;
    require(id != null, "INVALID_REFERENCE", "New-person references must be declared before use.");
    draft.exists(id);
    return id;
  }

  private Relationship resolveRelationship(
      RelationshipKind kind,
      String from,
      String to,
      Map<String, String> createdIds,
      GraphDraft draft) {
    return new Relationship(
        kind, resolvePersonId(from, createdIds, draft), resolvePersonId(to, createdIds, draft));
  }

  private void persist(Graph before, Graph after) {
    // Remove links before people; add people before links to preserve foreign keys.
    for (var relationship : before.parentEdges())
      if (!after.parentEdges().contains(relationship))
        db.update(
            "DELETE FROM parent_edge WHERE parent_id=? AND child_id=?",
            relationship.parentId(),
            relationship.childId());
    for (var relationship : before.spouseEdges())
      if (!after.spouseEdges().contains(relationship))
        db.update(
            "DELETE FROM spouse_edge WHERE person_a_id=? AND person_b_id=?",
            relationship.personAId(),
            relationship.personBId());
    for (var person : before.people())
      if (after.people().stream().noneMatch(current -> current.id().equals(person.id())))
        db.update("DELETE FROM person WHERE id=?", person.id());
    for (var person : after.people())
      if (!before.people().contains(person))
        db.update(
            "INSERT INTO person(id,name) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name",
            person.id(),
            person.name());
    for (var relationship : after.parentEdges())
      if (!before.parentEdges().contains(relationship))
        db.update(
            "INSERT INTO parent_edge VALUES (?,?)",
            relationship.parentId(),
            relationship.childId());
    for (var relationship : after.spouseEdges())
      if (!before.spouseEdges().contains(relationship))
        db.update(
            "INSERT INTO spouse_edge VALUES (?,?)",
            relationship.personAId(),
            relationship.personBId());
  }
}
