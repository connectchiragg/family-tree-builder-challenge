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
  return [...groups.entries()].map(([id, group]) => {
    const parents = group.parents.map(id => ({ x: positions.get(id).x + CARD_WIDTH / 2, y: positions.get(id).y + CARD_HEIGHT }));
    const children = group.children.map(id => ({ x: positions.get(id).x + CARD_WIDTH / 2, y: positions.get(id).y }));
    const parentBottom = Math.max(...parents.map(p => p.y));
    const childTop = Math.min(...children.map(p => p.y));
    const gap = childTop - parentBottom;
    const parentBar = parentBottom + gap / 3;
    const siblingBar = parentBottom + gap * 2 / 3;
    const parentLeft = Math.min(...parents.map(p => p.x));
    const parentRight = Math.max(...parents.map(p => p.x));
    const stem = (parentLeft + parentRight) / 2;
    const childLeft = Math.min(stem, ...children.map(p => p.x));
    const childRight = Math.max(stem, ...children.map(p => p.x));
    const path = [
      ...parents.map(p => `M ${p.x} ${p.y} V ${parentBar}`),
      `M ${parentLeft} ${parentBar} H ${parentRight}`,
      `M ${stem} ${parentBar} V ${siblingBar}`,
      `M ${childLeft} ${siblingBar} H ${childRight}`,
      ...children.map(p => `M ${p.x} ${siblingBar} V ${p.y}`),
    ].join(' ');
    return { id, ...group, path, parentBar, siblingBar, stem };
  });
}
