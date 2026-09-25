import { CARD_WIDTH, CARD_HEIGHT } from './familyLayout.js';

// Share a connector only when children have exactly the same recorded parents.
// Spouse relationships do not participate in this grouping.
export function familyConnectors(parentEdges, positions) {
  const parentsByChild = new Map();
  for (const { parentId, childId } of parentEdges) {
    if (!parentsByChild.has(childId)) parentsByChild.set(childId, new Set());
    parentsByChild.get(childId).add(parentId);
  }
  const groups = new Map();
  for (const [child, parents] of parentsByChild) {
    const ids = [...parents].sort();
    const key = JSON.stringify(ids);
    if (!groups.has(key)) groups.set(key, { parents: ids, children: [] });
    groups.get(key).children.push(child);
  }
  const routes = [...groups.entries()].map(([id, group]) => {
    const parents = group.parents.map(id => ({ x: positions.get(id).x + CARD_WIDTH / 2, y: positions.get(id).y + CARD_HEIGHT }));
    const children = group.children.map(id => ({ x: positions.get(id).x + CARD_WIDTH / 2, y: positions.get(id).y }));
    const parentBottom = Math.max(...parents.map(point => point.y));
    const childTop = Math.min(...children.map(point => point.y));
    const gap = childTop - parentBottom;
    const parentBar = parentBottom + gap / 3;
    const siblingBar = parentBottom + gap * 2 / 3;
    const parentLeft = Math.min(...parents.map(point => point.x));
    const parentRight = Math.max(...parents.map(point => point.x));
    const stem = (parentLeft + parentRight) / 2;
    const childLeft = Math.min(stem, ...children.map(point => point.x));
    const childRight = Math.max(stem, ...children.map(point => point.x));
    return { id, ...group, parentsPoints: parents, childrenPoints: children, parentBottom, childTop, parentLeft, parentRight, childLeft, childRight, parentBar, siblingBar, stem };
  });

  // Distinct lanes prevent unrelated sibling bars from merging when spouses
  // bring children of different families into an interleaved row.
  for (const route of routes) {
    const peers = routes.filter(other => other.parentBottom === route.parentBottom && other.childTop === route.childTop);
    const lane = peers.indexOf(route);
    const gap = route.childTop - route.parentBottom;
    route.parentBar = route.parentBottom + gap * (peers.length === 1 ? 1 / 3 : 0.2 + 0.2 * lane / (peers.length - 1));
    route.siblingBar = route.parentBottom + gap * (peers.length === 1 ? 2 / 3 : 0.55 + 0.3 * lane / (peers.length - 1));
    route.horizontal = [[route.parentLeft, route.parentRight, route.parentBar], [route.childLeft, route.childRight, route.siblingBar]];
  }
  return routes.map(route => {
    const { parentsPoints: parents, childrenPoints: children, parentLeft, parentRight, childLeft, childRight, parentBar, siblingBar, stem } = route;
    const otherFamilyBars = routes.filter(other => other !== route).flatMap(other => other.horizontal);
    const vertical = (x, from, to) => verticalWithCrossingGaps(x, from, to, otherFamilyBars);
    const path = [
      ...parents.map(point => vertical(point.x, point.y, parentBar)),
      `M ${parentLeft} ${parentBar} H ${parentRight}`,
      vertical(stem, parentBar, siblingBar),
      `M ${childLeft} ${siblingBar} H ${childRight}`,
      ...children.map(point => vertical(point.x, siblingBar, point.y)),
    ].join(' ');
    return { id: route.id, parents: route.parents, children: route.children, path, parentBar, siblingBar, stem };
  });
}

// A gap marks a crossing, not a shared ancestor. Our own family junctions stay connected.
function verticalWithCrossingGaps(x, from, to, otherFamilyBars) {
  const top = Math.min(from, to);
  const bottom = Math.max(from, to);
  const crossings = otherFamilyBars
    .filter(([left, right, y]) => left <= x && x <= right && top < y && y < bottom)
    .map(([, , y]) => y).sort((a, b) => a - b);
  let cursor = top;
  const segments = [];
  for (const crossingY of crossings) {
    const gapStart = Math.max(top, crossingY - 4);
    if (gapStart > cursor) segments.push(`M ${x} ${cursor} V ${gapStart}`);
    cursor = Math.max(cursor, Math.min(bottom, crossingY + 4));
  }
  if (cursor < bottom) segments.push(`M ${x} ${cursor} V ${bottom}`);
  return segments.join(' ');
}
