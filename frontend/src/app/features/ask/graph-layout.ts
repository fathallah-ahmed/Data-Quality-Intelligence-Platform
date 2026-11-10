import { SubgraphEdge, SubgraphNode } from '../../core/models';

/** A node as the drawing keeps it: the data of the graph plus a position and a speed. */
export interface PlacedNode extends SubgraphNode {
  x: number;
  y: number;
  vx: number;
  vy: number;
  /** True while the person holds it: the layout does not move it. */
  pinned: boolean;
}

const REPULSION = 5200;
const SPRING = 0.04;
const SPRING_LENGTH = 95;
const GRAVITY = 0.012;
const DAMPING = 0.82;

/** Adds nodes to a drawing: a new node starts near a node it is linked to, so it does not fly in from a corner. */
export function mergeNodes(
  current: PlacedNode[],
  added: SubgraphNode[],
  edges: SubgraphEdge[],
  width: number,
  height: number
): PlacedNode[] {
  const byId = new Map(current.map((node) => [node.id, node]));
  const merged = [...current];
  let index = current.length;
  for (const node of added) {
    if (byId.has(node.id)) {
      continue;
    }
    const link = edges.find((edge) => (edge.from === node.id ? byId.get(edge.to) : edge.to === node.id ? byId.get(edge.from) : undefined));
    const anchor = link ? byId.get(link.from === node.id ? link.to : link.from) : undefined;
    const angle = index * 2.399963; // golden angle: neighbours spread out instead of stacking
    const placed: PlacedNode = {
      ...node,
      x: (anchor?.x ?? width / 2) + Math.cos(angle) * 40,
      y: (anchor?.y ?? height / 2) + Math.sin(angle) * 40,
      vx: 0,
      vy: 0,
      pinned: false
    };
    byId.set(node.id, placed);
    merged.push(placed);
    index += 1;
  }
  return merged;
}

/** Adds edges whose two ends are known and which are not already there. */
export function mergeEdges(current: SubgraphEdge[], added: SubgraphEdge[], nodes: PlacedNode[]): SubgraphEdge[] {
  const known = new Set(nodes.map((node) => node.id));
  const seen = new Set(current.map((edge) => edge.id));
  const merged = [...current];
  for (const edge of added) {
    if (!seen.has(edge.id) && known.has(edge.from) && known.has(edge.to)) {
      seen.add(edge.id);
      merged.push(edge);
    }
  }
  return merged;
}

/**
 * One step of a force layout: nodes push each other away, linked nodes pull together, everything is drawn gently to the
 * centre. Returns the largest speed, so the caller can stop when the drawing has settled.
 */
export function layoutStep(nodes: PlacedNode[], edges: SubgraphEdge[], width: number, height: number): number {
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      const a = nodes[i];
      const b = nodes[j];
      let dx = a.x - b.x;
      let dy = a.y - b.y;
      let distance2 = dx * dx + dy * dy;
      if (distance2 < 1) {
        dx = Math.random() - 0.5;
        dy = Math.random() - 0.5;
        distance2 = 1;
      }
      const distance = Math.sqrt(distance2);
      const force = REPULSION / distance2;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;
      a.vx += fx;
      a.vy += fy;
      b.vx -= fx;
      b.vy -= fy;
    }
  }
  const byId = new Map(nodes.map((node) => [node.id, node]));
  for (const edge of edges) {
    const a = byId.get(edge.from);
    const b = byId.get(edge.to);
    if (!a || !b || a === b) {
      continue;
    }
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const distance = Math.sqrt(dx * dx + dy * dy) || 1;
    const pull = (distance - SPRING_LENGTH) * SPRING;
    a.vx += (dx / distance) * pull;
    a.vy += (dy / distance) * pull;
    b.vx -= (dx / distance) * pull;
    b.vy -= (dy / distance) * pull;
  }
  let fastest = 0;
  for (const node of nodes) {
    if (node.pinned) {
      node.vx = 0;
      node.vy = 0;
      continue;
    }
    node.vx = (node.vx + (width / 2 - node.x) * GRAVITY) * DAMPING;
    node.vy = (node.vy + (height / 2 - node.y) * GRAVITY) * DAMPING;
    node.x += node.vx;
    node.y += node.vy;
    fastest = Math.max(fastest, Math.abs(node.vx), Math.abs(node.vy));
  }
  return fastest;
}

/** A colour for each label, the same every time for the same position in the list. */
export const LABEL_COLOURS = ['#662583', '#e89900', '#6f8d33', '#e40613', '#d96aa0', '#0b7fc4', '#94c120', '#8c8398'];

export function labelColour(labels: string[], label: string): string {
  const index = Math.max(0, labels.indexOf(label));
  return LABEL_COLOURS[index % LABEL_COLOURS.length];
}
