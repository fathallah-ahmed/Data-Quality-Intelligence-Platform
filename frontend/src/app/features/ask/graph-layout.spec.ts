import { describe, expect, it } from 'vitest';
import { SubgraphEdge, SubgraphNode } from '../../core/models';
import { labelColour, layoutStep, mergeEdges, mergeNodes } from './graph-layout';

const node = (id: string, label = 'Customer'): SubgraphNode => ({ id, label, caption: id, conflict: false, properties: {} });
const edge = (id: string, from: string, to: string): SubgraphEdge => ({ id, from, to, type: 'HAS_ORDER' });

describe('graph layout', () => {
  it('starts a new node next to the node it is linked to', () => {
    const first = mergeNodes([], [node('a')], [], 760, 440);
    first[0].x = 100;
    first[0].y = 100;
    const merged = mergeNodes(first, [node('b', 'Order')], [edge('e', 'a', 'b')], 760, 440);
    const b = merged.find((item) => item.id === 'b')!;
    expect(Math.hypot(b.x - 100, b.y - 100)).toBeLessThan(60);
  });

  it('keeps an existing node and drops an edge whose end is unknown', () => {
    const nodes = mergeNodes([], [node('a'), node('b')], [], 760, 440);
    expect(mergeNodes(nodes, [node('a')], [], 760, 440)).toHaveLength(2);
    const edges = mergeEdges([], [edge('e1', 'a', 'b'), edge('e2', 'a', 'zzz')], nodes);
    expect(edges.map((item) => item.id)).toEqual(['e1']);
    expect(mergeEdges(edges, [edge('e1', 'a', 'b')], nodes)).toHaveLength(1);
  });

  it('settles: linked nodes end up apart but close, and a pinned node does not move', () => {
    const nodes = mergeNodes([], [node('a'), node('b')], [edge('e', 'a', 'b')], 760, 440);
    nodes[0].pinned = true;
    const pinnedAt = [nodes[0].x, nodes[0].y];
    let fastest = Infinity;
    for (let i = 0; i < 400 && fastest > 0.05; i++) {
      fastest = layoutStep(nodes, [edge('e', 'a', 'b')], 760, 440);
    }
    const distance = Math.hypot(nodes[0].x - nodes[1].x, nodes[0].y - nodes[1].y);
    expect(distance).toBeGreaterThan(30);
    expect(distance).toBeLessThan(200);
    expect([nodes[0].x, nodes[0].y]).toEqual(pinnedAt);
  });

  it('gives a label the same colour every time', () => {
    expect(labelColour(['Customer', 'Order'], 'Order')).toBe(labelColour(['Customer', 'Order'], 'Order'));
    expect(labelColour(['Customer', 'Order'], 'Order')).not.toBe(labelColour(['Customer', 'Order'], 'Customer'));
  });
});

import { rowCells } from './ask-tab';

describe('result rows', () => {
  it('reads a row sent as an object in the order of the columns, and still accepts an array', () => {
    expect(rowCells({ b: 2, a: 1 }, ['a', 'b'])).toEqual([1, 2]);
    expect(rowCells({ a: 1 }, ['a', 'b'])).toEqual([1, undefined]);
    expect(rowCells([7, 8], ['a', 'b'])).toEqual([7, 8]);
  });
});
