import test from 'node:test';
import assert from 'node:assert/strict';
import { familyLayout, CARD_WIDTH, CARD_HEIGHT } from './familyLayout.js';

const person = id => ({ id, name: id });
const parent = (parentId, childId) => ({ parentId, childId });
const spouse = (personAId, personBId) => ({ personAId, personBId });
const graph = (ids, parentEdges = [], spouseEdges = []) => ({ people: ids.map(person), parentEdges, spouseEdges });
function assertLayout(data) {
  const result = familyLayout(data);
  for (const edge of data.parentEdges) assert.ok(result.positions.get(edge.parentId).y < result.positions.get(edge.childId).y);
  const positions = [...result.positions.values()];
  positions.forEach((a, i) => positions.slice(i + 1).forEach(b => {
    assert.ok(Math.abs(a.x - b.x) >= CARD_WIDTH || Math.abs(a.y - b.y) >= CARD_HEIGHT, 'Cards overlap');
  }));
  return result;
}
test('parents and spouses sit above siblings; no inferred marriage', () => {
  const data = graph(['a', 'b', 'c', 'd'], [parent('a','c'),parent('b','c'),parent('a','d'),parent('b','d')], [spouse('a','b')]);
  const before = structuredClone(data);
  const { positions } = assertLayout(data);
  assert.equal(positions.get('a').y, positions.get('b').y);
  assert.equal(positions.get('c').y, positions.get('d').y);
  assert.deepEqual(data, before);
});
test('disconnected families occupy separate regions', () => {
  const { families } = assertLayout(graph(['a','b','c','d','e'], [parent('a','b'),parent('c','d')]));
  assert.equal(families.length, 3);
  for (let i=1;i<families.length;i++) assert.ok(families[i].x > families[i-1].x + families[i-1].width);
});
test('shared ancestors and multiple generations keep every parent above its children', () => {
  assertLayout(graph(['a','b','c','d','e','f'], [parent('a','c'),parent('b','c'),parent('c','e'),parent('d','e'),parent('c','f'),parent('d','f')], [spouse('a','b'),spouse('c','d')]));
});
test('cross-generation spouse grouping falls back without losing ancestry', () => {
  const { positions } = assertLayout(graph(['a','b','c'],[parent('a','b'),parent('b','c')],[spouse('a','c')]));
  assert.ok(positions.get('a').y < positions.get('c').y);
});
test('empty graph and duplicate names preserve identities', () => {
  assert.equal(familyLayout(graph([])).positions.size,0);
  const data=graph(['a','b']); data.people.forEach(p=>p.name='John');
  const result=assertLayout(data); assert.equal(result.positions.size,2);
});
