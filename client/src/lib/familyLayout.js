export const CARD_WIDTH = 168;
export const CARD_HEIGHT = 64;
const GAP = 20;
const BRANCH_GAP = 96;
const ROW_HEIGHT = 164;

// Lay out connected families separately. Spouses and co-parents share a row;
// grouping affects presentation only and never creates a relationship.
export function familyLayout({ people, parentEdges, spouseEdges }) {
  const peopleById = new Map(people.map(person => [person.id, person]));
  const comparePeople = (a, b) => peopleById.get(a).name.localeCompare(peopleById.get(b).name) || a.localeCompare(b);
  const components = connectedFamilies([...peopleById.keys()], parentEdges, spouseEdges, comparePeople);

  const positions = new Map();
  const families = [];
  let familyOffset = 0;
  for (const members of components) {
    const familyMembers = new Set(members);
    const parents = parentEdges.filter(edge => familyMembers.has(edge.parentId) && familyMembers.has(edge.childId));
    const spouses = spouseEdges.filter(edge => familyMembers.has(edge.personAId) && familyMembers.has(edge.personBId));
    const groupLeaders = new Map(members.map(id => [id, id]));
    const groupOf = id => {
      while (groupLeaders.get(id) !== id) id = groupLeaders.get(id);
      return id;
    };
    const mergeGroups = (a, b) => groupLeaders.set(groupOf(b), groupOf(a));
    spouses.forEach(edge => mergeGroups(edge.personAId, edge.personBId));
    const firstParentByChild = new Map();
    parents.forEach(edge => {
      const previous = firstParentByChild.get(edge.childId);
      if (previous) mergeGroups(previous, edge.parentId);
      firstParentByChild.set(edge.childId, edge.parentId);
    });
    let groups = groupBy(members, groupOf);
    let rankedGenerations = generationRanks(groups, parents, groupOf);
    // A valid parent DAG can contain spouses in different generations. If
    // collapsing them creates a cycle, preserve ancestry instead of hiding it.
    if (!rankedGenerations) {
      members.forEach(id => groupLeaders.set(id, id));
      groups = groupBy(members, groupOf);
      rankedGenerations = generationRanks(groups, parents, groupOf);
    }
    const generations = rankedGenerations || new Map(members.map(id => [id, 0]));
    const rows = groupBy([...groups.keys()], id => generations.get(id));
    const groupWidth = id => groups.get(id).length * (CARD_WIDTH + GAP) - GAP;
    const rowWidth = ids => ids.reduce((sum, id) => sum + groupWidth(id), 0) + Math.max(0, ids.length - 1) * BRANCH_GAP;
    const width = Math.max(CARD_WIDTH, ...[...rows.values()].map(rowWidth));
    const groupCenters = new Map();
    let rowY = 0;
    for (const [generation, row] of [...rows.entries()].sort((a, b) => a[0] - b[0])) {
      const averageParentCenter = id => {
        const parentCenters = parents
          .filter(edge => groupOf(edge.childId) === id)
          .map(edge => groupCenters.get(groupOf(edge.parentId)))
          .filter(center => center !== undefined);
        return parentCenters.length
          ? parentCenters.reduce((sum, center) => sum + center, 0) / parentCenters.length
          : 0;
      };
      row.sort((a, b) => averageParentCenter(a) - averageParentCenter(b) || comparePeople(groups.get(a)[0], groups.get(b)[0]));
      let x = familyOffset + (width - rowWidth(row)) / 2;
      for (const group of row) {
        const ordered = spousesTogether(groups.get(group), spouses, comparePeople);
        groupCenters.set(group, x + (ordered.length * (CARD_WIDTH + GAP) - GAP) / 2);
        ordered.forEach(id => {
          positions.set(id, { x, y: rowY });
          x += CARD_WIDTH + GAP;
        });
        x += BRANCH_GAP - GAP;
      }
      // More independent parent sets need more room for separate connector lanes.
      const parentSets = new Map();
      parents.filter(edge => generations.get(groupOf(edge.parentId)) === generation).forEach(edge => {
        if (!parentSets.has(edge.childId)) parentSets.set(edge.childId, []);
        parentSets.get(edge.childId).push(edge.parentId);
      });
      const lanes = new Set([...parentSets.values()].map(ids => JSON.stringify(ids.sort()))).size;
      rowY += ROW_HEIGHT + Math.max(0, lanes - 1) * 100;
    }
    families.push({ ids: members, x: familyOffset, width });
    familyOffset += width + 160;
  }
  return { positions, families };
}

function groupBy(items, key) {
  const groups = new Map();
  for (const item of items) {
    const groupKey = key(item);
    if (!groups.has(groupKey)) groups.set(groupKey, []);
    groups.get(groupKey).push(item);
  }
  return groups;
}

// Each child sits below its deepest parent. A cycle means the visual grouping is invalid.
function generationRanks(groups, edges, groupOf) {
  const incoming = new Map([...groups.keys()].map(id => [id, new Set()]));
  const outgoing = new Map([...groups.keys()].map(id => [id, new Set()]));
  for (const edge of edges) {
    const parentGroup = groupOf(edge.parentId);
    const childGroup = groupOf(edge.childId);
    if (parentGroup === childGroup) return null;
    incoming.get(childGroup).add(parentGroup);
    outgoing.get(parentGroup).add(childGroup);
  }
  const generations = new Map(), ready = [...groups.keys()].filter(id => !incoming.get(id).size);
  ready.forEach(id => generations.set(id, 0));
  for (let i = 0; i < ready.length; i++) {
    const id = ready[i];
    for (const child of outgoing.get(id)) {
      generations.set(child, Math.max(generations.get(child) || 0, generations.get(id) + 1));
      incoming.get(child).delete(id);
      if (!incoming.get(child).size) ready.push(child);
    }
  }
  return ready.length === groups.size ? generations : null;
}

// Unconnected families get separate canvas regions so their branches cannot overlap.
function connectedFamilies(personIds, parentEdges, spouseEdges, compare) {
  const links = new Map(personIds.map(id => [id, new Set()]));
  for (const [a, b] of [...parentEdges.map(edge => [edge.parentId, edge.childId]), ...spouseEdges.map(edge => [edge.personAId, edge.personBId])]) {
    if (links.has(a) && links.has(b)) {
      links.get(a).add(b);
      links.get(b).add(a);
    }
  }
  const families = [];
  const visited = new Set();
  for (const id of [...personIds].sort(compare)) {
    if (visited.has(id)) continue;
    const members = [], pending = [id];
    while (pending.length) {
      const next = pending.pop();
      if (visited.has(next)) continue;
      visited.add(next);
      members.push(next);
      pending.push(...links.get(next));
    }
    families.push(members.sort(compare));
  }

  return families;
}

// Keep married partners adjacent without implying that they share every child.
function spousesTogether(members, spouses, compare) {
  const remaining = new Set(members.sort(compare));
  const ordered = [];
  while (remaining.size) {
    const id = remaining.values().next().value;
    ordered.push(id);
    remaining.delete(id);
    const pair = spouses.find(edge => edge.personAId === id || edge.personBId === id);
    const partner = pair && (pair.personAId === id ? pair.personBId : pair.personAId);
    if (remaining.has(partner)) {
      ordered.push(partner);
      remaining.delete(partner);
    }
  }
  return ordered;
}
