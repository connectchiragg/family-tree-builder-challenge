import { useEffect, useState, useMemo, useCallback } from "react";
import { ReactFlow, Background, Controls, Handle, Position, useReactFlow, useNodesInitialized } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
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
  const [view, setView] = useState("tree");
  const load = useCallback(async () => {
    try {
      const data = await fetchGraph();
      setGraph(previous => JSON.stringify(previous) === JSON.stringify(data) ? previous : data);
      setError(null);
    } catch { setError("Couldn't load the family tree."); }
  }, []);
  useEffect(() => { load(); }, [load, refreshSignal]);
  useEffect(() => { const timer = setInterval(load, 4000); return () => clearInterval(timer); }, [load]);

  const { nodes, edges, families, relations } = useMemo(() => {
    const { positions, families } = familyLayout(graph);
    const names = new Map(graph.people.map(p => [p.id, p.name]));
    const relations = new Map(graph.people.map(p => [p.id, { parents: [], children: [], spouses: [] }]));
    graph.parentEdges.forEach(e => {
      relations.get(e.childId)?.parents.push(e.parentId);
      relations.get(e.parentId)?.children.push(e.childId);
    });
    graph.spouseEdges.forEach(e => {
      relations.get(e.personAId)?.spouses.push(e.personBId);
      relations.get(e.personBId)?.spouses.push(e.personAId);
    });
    const describe = ids => ids.map(id => names.get(id)).join(" & ");
    const nodes = graph.people.map(p => ({
      id: p.id, type: "person", position: positions.get(p.id),
      data: { name: p.name, context: relations.get(p.id).parents.length ? `Child of ${describe(relations.get(p.id).parents)}` : "No parents recorded" },
      width: CARD_WIDTH, height: CARD_HEIGHT,
    }));
    const edges = graph.parentEdges.map(e => ({
      id: `parent-${e.parentId}-${e.childId}`, source: e.parentId, target: e.childId,
      sourceHandle: "parent", targetHandle: "child", type: "smoothstep",
      pathOptions: { borderRadius: 0 }, className: "parent-line",
      ariaLabel: `${names.get(e.parentId)} is a parent of ${names.get(e.childId)}`,
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
    return { nodes, edges, families, relations };
  }, [graph]);
  const names = new Map(graph.people.map(p => [p.id, p.name]));
  const label = ids => ids.length ? ids.map(id => names.get(id)).join(", ") : "None recorded";

  return <section className="graph-panel" aria-label="Family relationships">
    <div className="family-toolbar">
      <div><h2>Your family</h2><p>{graph.people.length} people · {families.length} family {families.length === 1 ? "group" : "groups"}</p></div>
      <div className="view-switch" aria-label="Family view">
        <button onClick={() => setView("tree")} aria-pressed={view === "tree"}>Tree</button>
        <button onClick={() => setView("list")} aria-pressed={view === "list"}>List</button>
      </div>
    </div>
    {error && <div className="chat-error" role="alert">{error}</div>}
    {graph.people.length === 0 ? <div className="family-empty"><strong>Your family starts here</strong><p>Tell the assistant about a person and their relationships.</p></div>
      : view === "tree" ? <>
        <div className="tree-legend"><span><i className="parent-key" /> Parent to child, top to bottom</span><span><i className="spouse-key" /> Spouses</span></div>
        <div className="tree-canvas">
          <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView minZoom={0.2} maxZoom={1.5}
            nodesDraggable={false} nodesConnectable={false} edgesReconnectable={false} elementsSelectable={false}>
            <Background gap={24} size={1} /><Controls showInteractive={false} /><FitTree revision={graph} />
          </ReactFlow>
        </div>
      </> : <div className="relationship-list">
        {families.map((family, index) => <section className="family-list-group" key={family.ids[0]}>
          <h3>Family group {index + 1}</h3>
          {family.ids.map(id => <article className="relationship-person" key={id}>
            <h4>{names.get(id)}</h4>
            <dl><div><dt>Parents</dt><dd>{label(relations.get(id).parents)}</dd></div>
              <div><dt>Spouse</dt><dd>{label(relations.get(id).spouses)}</dd></div>
              <div><dt>Children</dt><dd>{label(relations.get(id).children)}</dd></div></dl>
          </article>)}
        </section>)}
      </div>}
  </section>;
}
