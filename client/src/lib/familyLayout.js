export const CARD_WIDTH = 168;
export const CARD_HEIGHT = 64;
const GAP = 20;
const ROW_HEIGHT = 164;

// Lay out connected families separately. Spouses and co-parents share a row;
// grouping affects presentation only and never creates a relationship.
export function familyLayout({ people, parentEdges, spouseEdges }) {
  const byId = new Map(people.map(p => [p.id, p]));
  const compare = (a, b) => byId.get(a).name.localeCompare(byId.get(b).name) || a.localeCompare(b);
  const links = new Map(people.map(p => [p.id, new Set()]));
  for (const [a, b] of [...parentEdges.map(e => [e.parentId, e.childId]), ...spouseEdges.map(e => [e.personAId, e.personBId])]) {
    if (links.has(a) && links.has(b)) { links.get(a).add(b); links.get(b).add(a); }
  }
  const components = [];
  const visited = new Set();
  for (const id of [...byId.keys()].sort(compare)) {
    if (visited.has(id)) continue;
    const members = [], pending = [id];
    while (pending.length) {
      const next = pending.pop();
      if (visited.has(next)) continue;
      visited.add(next); members.push(next); pending.push(...links.get(next));
    }
    components.push(members.sort(compare));
  }

  const positions = new Map();
  const families = [];
  let offset = 0;
  for (const members of components) {
    const included = new Set(members);
    const parents = parentEdges.filter(e => included.has(e.parentId) && included.has(e.childId));
    const spouses = spouseEdges.filter(e => included.has(e.personAId) && included.has(e.personBId));
    const roots = new Map(members.map(id => [id, id]));
    const find = id => { while (roots.get(id) !== id) id = roots.get(id); return id; };
    const join = (a, b) => roots.set(find(b), find(a));
    spouses.forEach(e => join(e.personAId, e.personBId));
    const childParents = new Map();
    parents.forEach(e => {
      const previous = childParents.get(e.childId);
      if (previous) join(previous, e.parentId);
      childParents.set(e.childId, e.parentId);
    });
    let groups = groupBy(members, find);
    let ranked = rank(groups, parents, find);
    // A valid parent DAG can contain spouses in different generations. If
    // collapsing them creates a cycle, preserve ancestry instead of hiding it.
    if (!ranked) {
      members.forEach(id => roots.set(id, id));
      groups = groupBy(members, find);
      ranked = rank(groups, parents, find);
    }
    const ranks = ranked || new Map(members.map(id => [id, 0]));
    const rows = groupBy([...groups.keys()], id => ranks.get(id));
    const widthOf = ids => ids.reduce((sum, id) => sum + groups.get(id).length * (CARD_WIDTH + GAP), 0) - GAP;
    const width = Math.max(CARD_WIDTH, ...[...rows.values()].map(widthOf));
    const centers = new Map();
    for (const [generation, row] of [...rows.entries()].sort((a, b) => a[0] - b[0])) {
      const parentCenter = id => {
        const incoming = parents.filter(e => find(e.childId) === id).map(e => centers.get(find(e.parentId))).filter(x => x !== undefined);
        return incoming.length ? incoming.reduce((a, b) => a + b, 0) / incoming.length : 0;
      };
      row.sort((a, b) => parentCenter(a) - parentCenter(b) || compare(groups.get(a)[0], groups.get(b)[0]));
      let x = offset + (width - widthOf(row)) / 2;
      for (const group of row) {
        const remaining = new Set(groups.get(group).sort(compare));
        const ordered = [];
        while (remaining.size) {
          const id = remaining.values().next().value;
          ordered.push(id); remaining.delete(id);
          const pair = spouses.find(e => e.personAId === id || e.personBId === id);
          const partner = pair && (pair.personAId === id ? pair.personBId : pair.personAId);
          if (remaining.has(partner)) { ordered.push(partner); remaining.delete(partner); }
        }
        centers.set(group, x + (ordered.length * (CARD_WIDTH + GAP) - GAP) / 2);
        ordered.forEach(id => { positions.set(id, { x, y: generation * ROW_HEIGHT }); x += CARD_WIDTH + GAP; });
      }
    }
    families.push({ ids: members, x: offset, width });
    offset += width + 72;
  }
  return { positions, families };
}

function groupBy(items, key) {
  const groups = new Map();
  items.forEach(item => { const k = key(item); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(item); });
  return groups;
}

function rank(groups, edges, groupOf) {
  const incoming = new Map([...groups.keys()].map(id => [id, new Set()]));
  const outgoing = new Map([...groups.keys()].map(id => [id, new Set()]));
  for (const e of edges) {
    const a = groupOf(e.parentId), b = groupOf(e.childId);
    if (a === b) return null;
    incoming.get(b).add(a); outgoing.get(a).add(b);
  }
  const ranks = new Map(), ready = [...groups.keys()].filter(id => !incoming.get(id).size);
  ready.forEach(id => ranks.set(id, 0));
  for (let i = 0; i < ready.length; i++) {
    const id = ready[i];
    for (const child of outgoing.get(id)) {
      ranks.set(child, Math.max(ranks.get(child) || 0, ranks.get(id) + 1));
      incoming.get(child).delete(id);
      if (!incoming.get(child).size) ready.push(child);
    }
  }
  return ready.length === groups.size ? ranks : null;
}
