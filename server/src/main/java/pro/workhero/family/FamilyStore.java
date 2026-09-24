package pro.workhero.family;

import static pro.workhero.family.Family.*;

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
            (r, n) -> new Person(r.getString("id"), r.getString("name"))),
        db.query(
            "SELECT * FROM parent_edge ORDER BY parent_id, child_id",
            (r, n) -> new ParentEdge(r.getString(1), r.getString(2))),
        db.query(
            "SELECT * FROM spouse_edge ORDER BY person_a_id, person_b_id",
            (r, n) -> new SpouseEdge(r.getString(1), r.getString(2))));
  }

  public List<Person> find(String name) {
    return graph().people().stream().filter(p -> p.name().equalsIgnoreCase(name.strip())).toList();
  }

  public Person create(String name) {
    var person = new Person(UUID.randomUUID().toString(), validName(name));
    db.update("INSERT INTO person VALUES (?,?)", person.id(), person.name());
    return person;
  }

  public Person rename(String id, String name) {
    exists(id);
    var person = new Person(id, validName(name));
    db.update("UPDATE person SET name=? WHERE id=?", person.name(), id);
    return person;
  }

  public Graph add(Relationship edge) {
    validate(edge);
    var a = edge.fromId();
    var b = edge.toId();
    if (edge.kind().equals("parent")) {
      if (count("SELECT count(*) FROM parent_edge WHERE parent_id=? AND child_id=?", a, b) > 0)
        return graph();
      require(
          count("SELECT count(*) FROM parent_edge WHERE child_id=?", b) < 2,
          "PARENT_LIMIT",
          "A child can have at most two recorded parents.");
      require(!reaches(b, a), "CYCLE_DETECTED", "This parent relationship would create a cycle.");
      db.update("INSERT INTO parent_edge VALUES (?,?)", a, b);
    } else {
      var pair = ordered(edge);
      if (count(
              "SELECT count(*) FROM spouse_edge WHERE person_a_id=? AND person_b_id=?",
              pair[0],
              pair[1])
          > 0) return graph();
      require(
          count(
                  "SELECT count(*) FROM spouse_edge WHERE person_a_id IN (?,?) OR person_b_id IN (?,?)",
                  a,
                  b,
                  a,
                  b)
              == 0,
          "UNSUPPORTED_REMARRIAGE",
          "Multiple spouses are outside this exercise's scope.");
      db.update("INSERT INTO spouse_edge VALUES (?,?)", pair[0], pair[1]);
    }
    return graph();
  }

  public Graph remove(Relationship edge) {
    validate(edge);
    int removed;
    if (edge.kind().equals("parent"))
      removed =
          db.update(
              "DELETE FROM parent_edge WHERE parent_id=? AND child_id=?",
              edge.fromId(),
              edge.toId());
    else {
      var p = ordered(edge);
      removed =
          db.update("DELETE FROM spouse_edge WHERE person_a_id=? AND person_b_id=?", p[0], p[1]);
    }
    require(removed == 1, "RELATIONSHIP_NOT_FOUND", "The relationship to remove does not exist.");
    return graph();
  }

  public Graph replace(Relationship oldEdge, Relationship newEdge) {
    remove(oldEdge);
    return add(newEdge); // Same transaction: a failed replacement restores the original edge.
  }

  private boolean reaches(String start, String target) {
    var children = new HashMap<String, List<String>>();
    graph()
        .parentEdges()
        .forEach(
            e -> children.computeIfAbsent(e.parentId(), k -> new ArrayList<>()).add(e.childId()));
    var pending = new ArrayDeque<String>();
    var seen = new HashSet<String>();
    pending.add(start);
    while (!pending.isEmpty()) {
      var id = pending.removeFirst();
      if (id.equals(target)) return true;
      if (seen.add(id)) pending.addAll(children.getOrDefault(id, List.of()));
    }
    return false;
  }

  private void validate(Relationship e) {
    require(
        e != null && e.kind() != null && Set.of("parent", "spouse").contains(e.kind()),
        "INVALID_INPUT",
        "Relationship kind must be parent or spouse.");
    exists(e.fromId());
    exists(e.toId());
    require(
        !e.fromId().equals(e.toId()),
        "SELF_RELATIONSHIP",
        "A person cannot have a relationship to themselves.");
  }

  private void exists(String id) {
    require(
        id != null && count("SELECT count(*) FROM person WHERE id=?", id) == 1,
        "PERSON_NOT_FOUND",
        "Look up the person before using their ID.");
  }

  private int count(String sql, Object... args) {
    return db.queryForObject(sql, Integer.class, args);
  }

  private String[] ordered(Relationship e) {
    return e.fromId().compareTo(e.toId()) < 0
        ? new String[] {e.fromId(), e.toId()}
        : new String[] {e.toId(), e.fromId()};
  }

  private String validName(String name) {
    require(
        name != null && !name.isBlank() && name.strip().length() <= 120,
        "INVALID_INPUT",
        "Name must contain 1–120 characters.");
    return name.strip();
  }
}
