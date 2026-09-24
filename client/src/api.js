export async function sendChatMessage(messages) {
  const res = await fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ messages }),
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Chat request failed: ${res.status}`);
  }

  return res.json();
}

export async function fetchGraph() {
  const res = await fetch("/api/graph");

  if (!res.ok) {
    throw new Error(`Graph request failed: ${res.status}`);
  }

  return res.json();
}
