import test from 'node:test';
import assert from 'node:assert/strict';
import { familyConnectors } from './familyConnectors.js';
import { CARD_HEIGHT } from './familyLayout.js';
const edge = (parentId, childId) => ({ parentId, childId });
const positions = new Map([['a',{x:0,y:0}],['b',{x:208,y:0}],['c',{x:0,y:164}],['d',{x:208,y:164}]]);

test('two parents and two siblings share two bars and one central stem', () => {
  const connectors=familyConnectors([edge('a','c'),edge('b','c'),edge('a','d'),edge('b','d')],positions);
  assert.equal(connectors.length,1);
  const c=connectors[0];
  assert.deepEqual(c.parents,['a','b']);assert.deepEqual(c.children,['c','d']);
  assert.ok(CARD_HEIGHT < c.parentBar && c.parentBar < c.siblingBar && c.siblingBar < 164);
  assert.ok(c.path.includes(`M ${c.stem} ${c.parentBar} V ${c.siblingBar}`));
  assert.equal((c.path.match(/ H /g)||[]).length,2);
});
test('different parent sets remain separate and no parenthood is inferred', () => {
  const connectors=familyConnectors([edge('a','c'),edge('b','c'),edge('a','d')],positions);
  assert.equal(connectors.length,2);
  assert.deepEqual(connectors.find(c=>c.children.includes('d')).parents,['a']);
});
test('single parent/child and empty graph have valid connector output', () => {
  assert.equal(familyConnectors([],positions).length,0);
  const [c]=familyConnectors([edge('a','c')],positions);
  assert.ok(!/NaN|undefined/.test(c.path));assert.equal(c.stem,84);
});

test('interleaved families use separate bars and breaks at unrelated crossings', () => {
  const positions = new Map([
    ['a', {x:0,y:0}], ['b', {x:200,y:0}],
    ['e', {x:400,y:0}], ['f', {x:600,y:0}],
    ['c', {x:0,y:164}], ['g', {x:200,y:164}],
    ['d', {x:400,y:164}], ['h', {x:600,y:164}],
  ]);
  const edges = [['a','c'],['b','c'],['a','d'],['b','d'],
    ['e','g'],['f','g'],['e','h'],['f','h']].map(([p,c]) => edge(p,c));
  const [first, second] = familyConnectors(edges, positions);
  assert.ok(second.siblingBar - first.siblingBar >= 20);
  assert.notEqual(first.parentBar, second.parentBar);
  // The first family's right child leg crosses the second sibling bar.
  assert.ok(first.path.includes(`M 484 ${second.siblingBar + 4} V 164`));
  assert.ok(first.path.includes(`V ${second.siblingBar - 4}`));
  assert.deepEqual(first.children, ['c','d']);
  assert.deepEqual(second.children, ['g','h']);
});
