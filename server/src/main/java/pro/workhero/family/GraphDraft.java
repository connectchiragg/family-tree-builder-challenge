package pro.workhero.family;

import static pro.workhero.family.Family.*;

import java.util.*;

/** Pure domain validation on a private copy. No database writes happen here. */
final class GraphDraft {
  private final Map<String, Person> people = new LinkedHashMap<>();
  private final Set<ParentEdge> parents;
  private final Set<SpouseEdge> spouses;

  GraphDraft(Graph graph) {
    graph.people().forEach(p -> people.put(p.id(), p));
    parents = new LinkedHashSet<>(graph.parentEdges());
    spouses = new LinkedHashSet<>(graph.spouseEdges());
  }

  Person create(String name) {
    var person = new Person(UUID.randomUUID().toString(), validName(name));
    people.put(person.id(), person);
    return person;
  }

  void delete(String id) {
    exists(id);
    parents.removeIf(e -> e.parentId().equals(id) || e.childId().equals(id));
    spouses.removeIf(e -> e.personAId().equals(id) || e.personBId().equals(id));
    people.remove(id);
  }

  Person rename(String id, String name) {
    exists(id);
    var person = new Person(id, validName(name));
    people.put(id, person);
    return person;
  }

  void add(Relationship e) {
    validate(e);
    if (e.kind().equals("parent")) {
      var edge = new ParentEdge(e.fromId(), e.toId());
      if (parents.contains(edge)) return;
      require(
          parents.stream().filter(p -> p.childId().equals(e.toId())).count() < 2,
          "PARENT_LIMIT",
          "A child can have at most two recorded parents.");
      require(
          !reaches(e.toId(), e.fromId()),
          "CYCLE_DETECTED",
          "This parent relationship would create a cycle.");
      parents.add(edge);
    } else {
      var edge = ordered(e);
      if (spouses.contains(edge)) return;
      require(
          spouses.stream()
              .noneMatch(
                  s ->
                      Set.of(s.personAId(), s.personBId()).contains(e.fromId())
                          || Set.of(s.personAId(), s.personBId()).contains(e.toId())),
          "UNSUPPORTED_REMARRIAGE",
          "Multiple spouses are outside this exercise's scope.");
      spouses.add(edge);
    }
  }

  void remove(Relationship e) {
    validate(e);
    boolean removed =
        e.kind().equals("parent")
            ? parents.remove(new ParentEdge(e.fromId(), e.toId()))
            : spouses.remove(ordered(e));
    require(removed, "RELATIONSHIP_NOT_FOUND", "The relationship to remove does not exist.");
  }

  void replace(Relationship oldEdge, Relationship newEdge) {
    remove(oldEdge);
    add(newEdge);
  }

  void exists(String id) {
    require(
        id != null && people.containsKey(id),
        "PERSON_NOT_FOUND",
        "The referenced existing person does not exist. Ask the user to clarify; do not create a substitute.");
  }

  Graph graph() {
    return new Graph(List.copyOf(people.values()), List.copyOf(parents), List.copyOf(spouses));
  }

  private void validate(Relationship e) {
    require(
        e != null && ("parent".equals(e.kind()) || "spouse".equals(e.kind())),
        "INVALID_INPUT",
        "Relationship kind must be parent or spouse.");
    exists(e.fromId());
    exists(e.toId());
    require(
        !e.fromId().equals(e.toId()),
        "SELF_RELATIONSHIP",
        "A person cannot have a relationship to themselves.");
  }

  private boolean reaches(String start, String target) {
    var pending = new ArrayDeque<String>();
    var seen = new HashSet<String>();
    pending.add(start);
    while (!pending.isEmpty()) {
      var id = pending.removeFirst();
      if (id.equals(target)) return true;
      if (seen.add(id))
        parents.stream()
            .filter(e -> e.parentId().equals(id))
            .map(ParentEdge::childId)
            .forEach(pending::add);
    }
    return false;
  }

  private SpouseEdge ordered(Relationship e) {
    return e.fromId().compareTo(e.toId()) < 0
        ? new SpouseEdge(e.fromId(), e.toId())
        : new SpouseEdge(e.toId(), e.fromId());
  }

  private String validName(String name) {
    require(
        name != null && !name.isBlank() && name.strip().length() <= 120,
        "INVALID_INPUT",
        "Name must contain 1–120 characters.");
    return name.strip();
  }
}
