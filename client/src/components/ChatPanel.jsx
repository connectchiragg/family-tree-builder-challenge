import { useState, useRef, useEffect } from "react";
import { sendChatMessage } from "../api";

export default function ChatPanel({ onGraphMightHaveChanged }) {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState(null);
  const scrollRef = useRef(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [messages]);

  async function handleSubmit(e) {
    e.preventDefault();
    const text = input.trim();
    if (!text || isSending) return;

    const nextMessages = [...messages, { role: "user", content: text }];
    setMessages(nextMessages);
    setInput("");
    setIsSending(true);
    setError(null);

    try {
      const { reply } = await sendChatMessage(nextMessages);
      setMessages([...nextMessages, { role: "assistant", content: reply }]);
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
        {isSending && (
          <div className="chat-message chat-message--assistant chat-message--pending">
            <span className="chat-message__role">Assistant</span>
            <p>…</p>
          </div>
        )}
      </div>

      {error && <div className="chat-error">{error}</div>}

      <form className="chat-input" onSubmit={handleSubmit}>
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Tell it about your family..."
          disabled={isSending}
        />
        <button type="submit" disabled={isSending || !input.trim()}>
          Send
        </button>
      </form>
    </div>
  );
}
