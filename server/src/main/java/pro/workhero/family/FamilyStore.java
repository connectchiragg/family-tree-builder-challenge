package pro.workhero.family;

import static pro.workhero.family.Family.*;

import java.util.*;
import java.util.function.Function;
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
            (r, n) -> new Person(r.getString("id"), r.getString("name"))),
        db.query(
            "SELECT * FROM parent_edge ORDER BY parent_id, child_id",
            (r, n) -> new ParentEdge(r.getString(1), r.getString(2))),
        db.query(
            "SELECT * FROM spouse_edge ORDER BY person_a_id, person_b_id",
            (r, n) -> new SpouseEdge(r.getString(1), r.getString(2))));
  }

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
    var refs = new LinkedHashMap<String, String>();
    for (var op : plan.operations()) {
      require(op != null, "INVALID_INPUT", "An operation cannot be null.");
      switch (op) {
        case Plan.CreatePerson c -> {
          require(
              c.ref() != null
                  && c.ref().matches("@[A-Za-z][A-Za-z0-9_-]{0,39}")
                  && !refs.containsKey(c.ref()),
              "INVALID_REFERENCE",
              "Each new person needs a unique @reference.");
          refs.put(c.ref(), draft.create(c.name()).id());
        }
        case Plan.DeletePerson d -> draft.delete(resolve(d.person(), refs, draft));
        case Plan.RenamePerson r -> draft.rename(resolve(r.person(), refs, draft), r.name());
        case Plan.AddRelationship a -> draft.add(edge(a.kind(), a.from(), a.to(), refs, draft));
        case Plan.RemoveRelationship r ->
            draft.remove(edge(r.kind(), r.from(), r.to(), refs, draft));
        case Plan.ReplaceRelationship r ->
            draft.replace(
                edge(r.oldKind(), r.oldFrom(), r.oldTo(), refs, draft),
                edge(r.newKind(), r.newFrom(), r.newTo(), refs, draft));
      }
    }
    // Every operation has passed on the draft. Only now do writes begin.
    persist(before, draft.graph());
    return new Applied(Map.copyOf(refs), graph());
  }

  private String resolve(String ref, Map<String, String> refs, GraphDraft draft) {
    require(ref != null && !ref.isBlank(), "INVALID_REFERENCE", "A person reference is required.");
    var id = ref.startsWith("@") ? refs.get(ref) : ref;
    require(id != null, "INVALID_REFERENCE", "New-person references must be declared before use.");
    draft.exists(id);
    return id;
  }

  private Relationship edge(
      String kind, String from, String to, Map<String, String> refs, GraphDraft draft) {
    return new Relationship(kind, resolve(from, refs, draft), resolve(to, refs, draft));
  }

  public List<Person> find(String name) {
    return graph().people().stream().filter(p -> p.name().equalsIgnoreCase(name.strip())).toList();
  }

  public Person create(String name) {
    return change(d -> d.create(name));
  }

  public Person rename(String id, String name) {
    return change(d -> d.rename(id, name));
  }

  public Graph add(Relationship edge) {
    change(
        d -> {
          d.add(edge);
          return null;
        });
    return graph();
  }

  public Graph remove(Relationship edge) {
    change(
        d -> {
          d.remove(edge);
          return null;
        });
    return graph();
  }

  public Graph replace(Relationship oldEdge, Relationship newEdge) {
    change(
        d -> {
          d.replace(oldEdge, newEdge);
          return null;
        });
    return graph();
  }

  private <T> T change(Function<GraphDraft, T> operation) {
    var before = graph();
    var draft = new GraphDraft(before);
    var result = operation.apply(draft);
    persist(before, draft.graph());
    return result;
  }

  private void persist(Graph before, Graph after) {
    for (var e : before.parentEdges())
      if (!after.parentEdges().contains(e))
        db.update(
            "DELETE FROM parent_edge WHERE parent_id=? AND child_id=?", e.parentId(), e.childId());
    for (var e : before.spouseEdges())
      if (!after.spouseEdges().contains(e))
        db.update(
            "DELETE FROM spouse_edge WHERE person_a_id=? AND person_b_id=?",
            e.personAId(),
            e.personBId());
    for (var p : before.people())
      if (after.people().stream().noneMatch(current -> current.id().equals(p.id())))
        db.update("DELETE FROM person WHERE id=?", p.id());
    for (var p : after.people())
      if (!before.people().contains(p))
        db.update(
            "INSERT INTO person(id,name) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name",
            p.id(),
            p.name());
    for (var e : after.parentEdges())
      if (!before.parentEdges().contains(e))
        db.update("INSERT INTO parent_edge VALUES (?,?)", e.parentId(), e.childId());
    for (var e : after.spouseEdges())
      if (!before.spouseEdges().contains(e))
        db.update("INSERT INTO spouse_edge VALUES (?,?)", e.personAId(), e.personBId());
  }
}
