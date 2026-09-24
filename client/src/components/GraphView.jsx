import { useEffect, useState, useMemo, useCallback } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  MarkerType,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { fetchGraph } from "../api";

const GEN_HEIGHT = 140;
const NODE_WIDTH = 180;

// Rough generation-based layout: people with no recorded parent are
// generation 0, everyone else is one generation below their (first)
// parent. This is just enough to render whatever the candidate's backend
// returns readably — it is not part of the assignment.
function layout(people, parentEdges) {
  const childToParents = new Map();
  for (const edge of parentEdges) {
    if (!childToParents.has(edge.childId)) childToParents.set(edge.childId, []);
    childToParents.get(edge.childId).push(edge.parentId);
  }

  const generation = new Map();
  function generationOf(personId, guard = new Set()) {
    if (generation.has(personId)) return generation.get(personId);
    if (guard.has(personId)) return 0; // defensive: don't hang on a cycle
    guard.add(personId);

    const parents = childToParents.get(personId) || [];
    const gen = parents.length
      ? 1 + Math.max(...parents.map((p) => generationOf(p, new Set(guard))))
      : 0;

    generation.set(personId, gen);
    return gen;
  }

  const byGeneration = new Map();
  for (const person of people) {
    const gen = generationOf(person.id);
    if (!byGeneration.has(gen)) byGeneration.set(gen, []);
    byGeneration.get(gen).push(person);
  }

  const positions = new Map();
  for (const [gen, folks] of byGeneration.entries()) {
    folks.forEach((person, i) => {
      positions.set(person.id, {
        x: i * NODE_WIDTH,
        y: gen * GEN_HEIGHT,
      });
    });
  }

  return positions;
}

export default function GraphView({ refreshSignal }) {
  const [graph, setGraph] = useState({
    people: [],
    parentEdges: [],
    spouseEdges: [],
  });
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      const data = await fetchGraph();
      setGraph(data);
      setError(null);
    } catch (err) {
      console.error(err);
      setError("Couldn't load the family tree.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load, refreshSignal]);

  // Poll so edits from other tabs / the agent loop show up without a manual
  // reload. Candidates are free to replace this with something smarter
  // (websocket push, etc.) — it's just wiring, not part of the assignment.
  useEffect(() => {
    const id = setInterval(load, 4000);
    return () => clearInterval(id);
  }, [load]);

  const { nodes, edges } = useMemo(() => {
    const positions = layout(graph.people, graph.parentEdges);

    const nodes = graph.people.map((person) => ({
      id: person.id,
      data: { label: person.name },
      position: positions.get(person.id) || { x: 0, y: 0 },
      style: {
        border: "1px solid #6366f1",
        borderRadius: 8,
        padding: 8,
        background: "#eef2ff",
        color: "#1e1b4b",
        fontSize: 13,
      },
    }));

    const parentEdges = graph.parentEdges.map((e) => ({
      id: `p-${e.parentId}-${e.childId}`,
      source: e.parentId,
      target: e.childId,
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { stroke: "#6366f1" },
    }));

    const spouseEdges = graph.spouseEdges.map((e) => ({
      id: `s-${e.personAId}-${e.personBId}`,
      source: e.personAId,
      target: e.personBId,
      type: "straight",
      style: { stroke: "#ec4899", strokeDasharray: "4 4" },
    }));

    return { nodes, edges: [...parentEdges, ...spouseEdges] };
  }, [graph]);

  return (
    <div className="graph-panel">
      {error && <div className="chat-error">{error}</div>}
      {graph.people.length === 0 && !error && (
        <div className="graph-empty">
          No family tree data yet. As the assistant records people and
          relationships, they'll appear here.
        </div>
      )}
      <ReactFlow nodes={nodes} edges={edges} fitView>
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
}
