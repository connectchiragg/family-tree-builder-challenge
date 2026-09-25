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
const FIT_OPTIONS = { padding: 0.22, minZoom: 0.02, maxZoom: 1.1 };
const nodeTypes = { person: PersonNode };
function FamilyEdge({ id, data }) {
  return <BaseEdge id={id} path={data.path} style={{ stroke: "var(--accent)", strokeWidth: 1.7 }} />;
}
const edgeTypes = { family: FamilyEdge };

function FitTree({ revision }) {
  const { fitView } = useReactFlow();
  const initialized = useNodesInitialized();
  useEffect(() => {
    if (initialized) fitView(FIT_OPTIONS);
  }, [revision, initialized, fitView]);
  return null;
}

export default function GraphView({ refreshSignal }) {
  const [graph, setGraph] = useState({ people: [], parentEdges: [], spouseEdges: [] });
  const [error, setError] = useState(null);
  const [query, setQuery] = useState("");
  const [flow, setFlow] = useState(null);
  const [focusedId, setFocusedId] = useState(null);
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
    const namesById = new Map(graph.people.map(person => [person.id, person.name]));
    const parentsByChild = new Map(graph.people.map(person => [person.id, []]));
    graph.parentEdges.forEach(edge => {
      parentsByChild.get(edge.childId)?.push(edge.parentId);
    });
    const describe = ids => ids.map(id => namesById.get(id)).join(" & ");
    const nodes = graph.people.map(person => ({
      id: person.id, type: "person", position: positions.get(person.id),
      data: { name: person.name, context: parentsByChild.get(person.id).length ? `Child of ${describe(parentsByChild.get(person.id))}` : "No parents recorded" },
      width: CARD_WIDTH, height: CARD_HEIGHT,
    }));
    const connectors = familyConnectors(graph.parentEdges, positions);
    const edges = connectors.map(group => ({
      id: `family-${group.id}`, source: group.parents[0], target: group.children[0],
      sourceHandle: "parent", targetHandle: "child", type: "family",
      data: { path: group.path },
      ariaLabel: `${describe(group.parents)} are parents of ${describe(group.children)}`,
    }));
    graph.spouseEdges.forEach(edge => {
      if (connectors.some(group => group.parents.includes(edge.personAId) && group.parents.includes(edge.personBId))) return;
      const firstPartner = positions.get(edge.personAId);
      const secondPartner = positions.get(edge.personBId);
      const left = firstPartner.x <= secondPartner.x ? edge.personAId : edge.personBId;
      const right = left === edge.personAId ? edge.personBId : edge.personAId;
      edges.push({ id: `spouse-${left}-${right}`, source: left, target: right,
        sourceHandle: "spouse-right", targetHandle: "spouse-left", type: firstPartner.y === secondPartner.y ? "straight" : "smoothstep",
        className: "spouse-line", ariaLabel: `${namesById.get(left)} and ${namesById.get(right)} are spouses`,
      });
    });
    return { nodes, edges, families };
  }, [graph]);

  const matches = query.trim() ? nodes.filter(node =>
    node.data.name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
    .sort((a, b) => a.data.name.localeCompare(b.data.name)) : [];
  const focusPerson = node => {
    flow?.setCenter(node.position.x + CARD_WIDTH / 2, node.position.y + CARD_HEIGHT / 2,
      { zoom: 1.1, duration: 300 });
    setFocusedId(node.id);
    setQuery("");
  };

  return <section className="graph-panel" aria-label="Family relationships">
    <div className="family-toolbar">
      <div><h2>Your family</h2><p>{graph.people.length} people · {families.length} family {families.length === 1 ? "group" : "groups"}</p></div>
      <div className="person-search" onKeyDown={event => {
        if (event.key === "Escape") setQuery("");
      }}>
        <input type="search" aria-label="Search people" placeholder="Search people…"
          value={query} onChange={event => setQuery(event.target.value)}
          onKeyDown={event => {
            if (event.key === "Enter" && matches.length === 1) focusPerson(matches[0]);
          }} />
        {query.trim() && <div className="person-search-results" aria-label="Matching people">
          {matches.length ? matches.map((node, index) => <button type="button" key={node.id}
            onClick={() => focusPerson(node)}>
            <strong>{node.data.name}</strong><span>{node.data.context}</span>
            {matches.filter(other => other.data.name === node.data.name).length > 1 &&
              <small>Match {index + 1}</small>}
          </button>) : <p role="status">No people found.</p>}
        </div>}
      </div>
    </div>
    {error && <div className="chat-error" role="alert">{error}</div>}
    {graph.people.length === 0 ? <div className="family-empty"><strong>Your family starts here</strong><p>Tell the assistant about a person and their relationships.</p></div>
      : <>
        <div className="tree-canvas">
          <ReactFlow onInit={setFlow} nodes={nodes.map(node => ({ ...node, className: node.id === focusedId ? "search-highlight" : "" }))} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} fitView fitViewOptions={FIT_OPTIONS} minZoom={0.02} maxZoom={1.5}
            nodesDraggable={false} nodesConnectable={false} edgesReconnectable={false} elementsSelectable={false}>
            <Background gap={24} size={1} /><Controls showInteractive={false} fitViewOptions={FIT_OPTIONS} /><FitTree revision={graph} />
          </ReactFlow>
        </div>
      </>}
  </section>;
}
