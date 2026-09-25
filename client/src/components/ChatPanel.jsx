import { useState, useRef, useEffect } from "react";
import { sendChatMessage, clearHistory } from "../api";

const STORAGE_KEY = "family-tree-conversation";
function loadConversation() {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}; }
  catch { return {}; }
}

export default function ChatPanel({ onGraphMightHaveChanged }) {
  const [messages, setMessages] = useState(() => loadConversation().messages || []);
  const [input, setInput] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState(null);
  const scrollRef = useRef(null);
  const [pending, setPending] = useState(() => loadConversation().pending || null);

  useEffect(() => {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ messages, pending })); }
    catch { /* Chat remains usable if browser storage is unavailable. */ }
  }, [messages, pending]);

  async function clearConversation() {
    setIsSending(true);
    try {
      await clearHistory();
      setMessages([]); setPending(null); setInput(""); setError(null);
    } catch (err) { setError(err.message); }
    finally { setIsSending(false); }
  }

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [messages]);

  async function handleSubmit(e) {
    e.preventDefault();
    const text = input.trim();
    if (!text || isSending || pending) return;

    const nextMessages = [...messages, { role: "user", content: text }];
    setMessages(nextMessages);
    setInput("");
    const request = { messages: nextMessages, id: crypto.randomUUID() };
    setPending(request);
    await send(request);
  }

  async function send(request) {
    if (isSending) return;
    setIsSending(true);
    setError(null);

    try {
      const { reply } = await sendChatMessage(request.messages, request.id);
      setMessages([...request.messages, { role: "assistant", content: reply }]);
      setPending(null);
      // The graph endpoint is polled independently, but nudging a refresh
      // right after a turn keeps the visualization feeling responsive once
      // the candidate's tool calls start actually mutating state.
      onGraphMightHaveChanged?.();
    } catch (err) {
      console.error(err);
      setError(err.message || "Something went wrong talking to the model.");
    } finally {
      setIsSending(false);
    }
  }

  return (
    <div className="chat-panel">
      <div className="chat-toolbar"><button type="button" disabled={isSending}
        onClick={clearConversation}>Clear conversation</button></div>
      <div className="chat-messages" ref={scrollRef}>
        {messages.length === 0 && (
          <div className="chat-empty">
            Describe your family — e.g. "My name is Alex. My parents are Sam
            and Jordan. I have a brother named John."
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`chat-message chat-message--${m.role}`}>
            <span className="chat-message__role">
              {m.role === "user" ? "You" : "Assistant"}
            </span>
            <p>{m.content}</p>
          </div>
        ))}
        {isSending && pending && (
          <div className="chat-message chat-message--assistant chat-message--pending">
            <span className="chat-message__role">Assistant</span>
            <p>…</p>
          </div>
        )}
      </div>

      {(error || pending) && !isSending && <div className="chat-error">
        {error || "A request is awaiting a reply. Retry to retrieve its result."}
        {pending && <button type="button" onClick={() => send(pending)}>Retry request</button>}
      </div>}

      <form className="chat-input" onSubmit={handleSubmit}>
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Tell it about your family..."
          disabled={isSending || !!pending}
        />
        <button type="submit" disabled={isSending || !!pending || !input.trim()}>
          Send
        </button>
      </form>
    </div>
  );
}
