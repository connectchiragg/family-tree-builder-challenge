import { useEffect, useState, useMemo, useCallback } from "react";
import { ReactFlow, BaseEdge, Background, Controls, Handle, Position, useReactFlow, useNodesInitialized } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { familyConnectors } from "../lib/familyConnectors";
import { fetchGraph } from "../api";
import { familyLayout, CARD_WIDTH, CARD_HEIGHT } from "../lib/familyLayout";

function PersonNode({ data }) {
  return <div className="person-card">
    <Handle type="target" position={Position.Top} id="child" />
    <Handle type="source" position={Position.Bottom} id="parent" />
    <Handle type="source" position={Position.Right} id="spouse-right" />
    <Handle type="target" position={Position.Left} id="spouse-left" />
    <strong title={data.name}>{data.name}</strong>
    <span>{data.context}</span>
  </div>;
}
const nodeTypes = { person: PersonNode };
function FamilyEdge({ id, data }) {
  return <BaseEdge id={id} path={data.path} style={{ stroke: "var(--accent)", strokeWidth: 1.7 }} />;
}
const edgeTypes = { family: FamilyEdge };

function FitTree({ revision }) {
  const { fitView } = useReactFlow();
  const initialized = useNodesInitialized();
  useEffect(() => {
    if (initialized) fitView({ padding: 0.18, maxZoom: 1.1, duration: 250 });
  }, [revision, initialized, fitView]);
  return null;
}

export default function GraphView({ refreshSignal }) {
  const [graph, setGraph] = useState({ people: [], parentEdges: [], spouseEdges: [] });
  const [error, setError] = useState(null);
  const load = useCallback(async () => {
    try {
      const data = await fetchGraph();
      setGraph(previous => JSON.stringify(previous) === JSON.stringify(data) ? previous : data);
      setError(null);
    } catch { setError("Couldn't load the family tree."); }
  }, []);
  useEffect(() => { load(); }, [load, refreshSignal]);
  useEffect(() => { const timer = setInterval(load, 4000); return () => clearInterval(timer); }, [load]);

  const { nodes, edges, families } = useMemo(() => {
    const { positions, families } = familyLayout(graph);
    const names = new Map(graph.people.map(p => [p.id, p.name]));
    const relations = new Map(graph.people.map(p => [p.id, { parents: [] }]));
    graph.parentEdges.forEach(e => {
      relations.get(e.childId)?.parents.push(e.parentId);
    });
    const describe = ids => ids.map(id => names.get(id)).join(" & ");
    const nodes = graph.people.map(p => ({
      id: p.id, type: "person", position: positions.get(p.id),
      data: { name: p.name, context: relations.get(p.id).parents.length ? `Child of ${describe(relations.get(p.id).parents)}` : "No parents recorded" },
      width: CARD_WIDTH, height: CARD_HEIGHT,
    }));
    const edges = familyConnectors(graph.parentEdges, positions).map(group => ({
      id: `family-${group.id}`, source: group.parents[0], target: group.children[0],
      sourceHandle: "parent", targetHandle: "child", type: "family",
      data: { path: group.path },
      ariaLabel: `${describe(group.parents)} are parents of ${describe(group.children)}`,
    }));
    graph.spouseEdges.forEach(e => {
      const a = positions.get(e.personAId), b = positions.get(e.personBId);
      const left = a.x <= b.x ? e.personAId : e.personBId;
      const right = left === e.personAId ? e.personBId : e.personAId;
      edges.push({ id: `spouse-${left}-${right}`, source: left, target: right,
        sourceHandle: "spouse-right", targetHandle: "spouse-left", type: a.y === b.y ? "straight" : "smoothstep",
        className: "spouse-line", ariaLabel: `${names.get(left)} and ${names.get(right)} are spouses`,
      });
    });
    return { nodes, edges, families };
  }, [graph]);

  return <section className="graph-panel" aria-label="Family relationships">
    <div className="family-toolbar">
      <div><h2>Your family</h2><p>{graph.people.length} people · {families.length} family {families.length === 1 ? "group" : "groups"}</p></div>

    </div>
    {error && <div className="chat-error" role="alert">{error}</div>}
    {graph.people.length === 0 ? <div className="family-empty"><strong>Your family starts here</strong><p>Tell the assistant about a person and their relationships.</p></div>
      : <>
        <div className="tree-legend"><span><i className="parent-key" /> Parent to child, top to bottom</span><span><i className="spouse-key" /> Spouses</span></div>
        <div className="tree-canvas">
          <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} fitView minZoom={0.2} maxZoom={1.5}
            nodesDraggable={false} nodesConnectable={false} edgesReconnectable={false} elementsSelectable={false}>
            <Background gap={24} size={1} /><Controls showInteractive={false} /><FitTree revision={graph} />
          </ReactFlow>
        </div>
      </>}
  </section>;
}
