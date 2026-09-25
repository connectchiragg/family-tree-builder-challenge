package pro.workhero.family;

import static pro.workhero.family.Family.*;
import static pro.workhero.family.InvalidFamilyOperationException.require;

import java.util.*;

/** Pure domain validation on a private copy. No database writes happen here. */
final class GraphDraft {
  private final Map<String, Person> people = new LinkedHashMap<>();
  private final Set<ParentEdge> parents;
  private final Set<SpouseEdge> spouses;

  GraphDraft(Graph graph) {
    graph.people().forEach(person -> people.put(person.id(), person));
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
    parents.removeIf(
        relationship -> relationship.parentId().equals(id) || relationship.childId().equals(id));
    spouses.removeIf(
        relationship -> relationship.personAId().equals(id) || relationship.personBId().equals(id));
    people.remove(id);
  }

  Person rename(String id, String name) {
    exists(id);
    var person = new Person(id, validName(name));
    people.put(id, person);
    return person;
  }

  void add(Relationship relationship) {
    validate(relationship);
    if (relationship.kind() == RelationshipKind.PARENT) addParent(relationship);
    else addSpouse(relationship);
  }

  private void addParent(Relationship relationship) {
    var edge = new ParentEdge(relationship.fromId(), relationship.toId());
    if (parents.contains(edge)) return;
    require(
        parents.stream().filter(parent -> parent.childId().equals(relationship.toId())).count() < 2,
        "PARENT_LIMIT",
        "A child can have at most two recorded parents.");
    var path = ancestryPath(relationship.toId(), relationship.fromId());
    require(
        path.isEmpty(),
        "CYCLE_DETECTED",
        "Cannot make "
            + people.get(relationship.fromId()).name()
            + " a parent of "
            + people.get(relationship.toId()).name()
            + ". The graph being validated already contains this "
            + "parent-to-child path: "
            + String.join(" → ", path.stream().map(id -> people.get(id).name()).toList())
            + ". Adding the reverse link would make someone their own ancestor.");
    parents.add(edge);
  }

  private void addSpouse(Relationship relationship) {
    var edge = canonicalSpouseEdge(relationship);
    if (spouses.contains(edge)) return;
    require(
        spouses.stream()
            .noneMatch(
                spouse ->
                    Set.of(spouse.personAId(), spouse.personBId()).contains(relationship.fromId())
                        || Set.of(spouse.personAId(), spouse.personBId())
                            .contains(relationship.toId())),
        "UNSUPPORTED_REMARRIAGE",
        "Multiple spouses are outside this exercise's scope.");
    spouses.add(edge);
  }

  void remove(Relationship relationship) {
    validate(relationship);
    boolean removed =
        relationship.kind() == RelationshipKind.PARENT
            ? parents.remove(new ParentEdge(relationship.fromId(), relationship.toId()))
            : spouses.remove(canonicalSpouseEdge(relationship));
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

  private void validate(Relationship relationship) {
    require(
        relationship != null && relationship.kind() != null,
        "INVALID_INPUT",
        "Relationship kind must be parent or spouse.");
    exists(relationship.fromId());
    exists(relationship.toId());
    require(
        !relationship.fromId().equals(relationship.toId()),
        "SELF_RELATIONSHIP",
        "A person cannot have a relationship to themselves.");
  }

  // If the proposed child already leads to the parent, adding the link would create a cycle.
  private List<String> ancestryPath(String descendantId, String ancestorId) {
    var peopleToVisit = new ArrayDeque<String>();
    var predecessor = new HashMap<String, String>();
    peopleToVisit.add(descendantId);
    predecessor.put(descendantId, null);
    while (!peopleToVisit.isEmpty()) {
      var id = peopleToVisit.removeFirst();
      if (id.equals(ancestorId)) {
        var path = new LinkedList<String>();
        for (var node = ancestorId; node != null; node = predecessor.get(node)) path.addFirst(node);
        return path;
      }
      for (var edge : parents) {
        if (edge.parentId().equals(id) && !predecessor.containsKey(edge.childId())) {
          predecessor.put(edge.childId(), id);
          peopleToVisit.add(edge.childId());
        }
      }
    }
    return List.of();
  }

  // Marriage has no direction: A–B and B–A must represent the same relationship.
  private SpouseEdge canonicalSpouseEdge(Relationship relationship) {
    return relationship.fromId().compareTo(relationship.toId()) < 0
        ? new SpouseEdge(relationship.fromId(), relationship.toId())
        : new SpouseEdge(relationship.toId(), relationship.fromId());
  }

  private String validName(String name) {
    require(
        name != null && !name.isBlank() && name.strip().length() <= 120,
        "INVALID_INPUT",
        "Name must contain 1–120 characters.");
    return name.strip();
  }
}
