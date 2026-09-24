const MAX_PROVIDER_TEXT = 8000;

function providerConfig() {
  const enabled = process.env.LUMO_AI_ENABLED === "true";
  const rawUrl = (process.env.LUMO_AI_PROVIDER_URL || "").trim();
  const apiKey = (process.env.LUMO_AI_PROVIDER_KEY || "").trim();
  const model = (process.env.LUMO_AI_MODEL || "").trim();
  if (!enabled || !rawUrl || !apiKey || !model) return null;
  let url;
  try { url = new URL(rawUrl); } catch { return null; }
  const localTest = process.env.NODE_ENV === "test" &&
    (url.hostname === "127.0.0.1" || url.hostname === "localhost");
  if (url.protocol !== "https:" && !(localTest && url.protocol === "http:")) return null;
  if (url.username || url.password) return null;
  return { url: url.toString(), apiKey, model };
}

export function aiReady() {
  return providerConfig() !== null;
}

/**
 * Calls a server-owner-configured OpenAI-compatible chat-completions endpoint.
 * The provider credential is never sent to Android and prompts are not logged here.
 */
export async function completeLumoAi(history, userText) {
  const config = providerConfig();
  if (!config) {
    const error = new Error("AI provider unavailable");
    error.code = "AI_UNAVAILABLE";
    throw error;
  }

  const messages = [
    {
      role: "system",
      content:
        "You are Lumo AI, a concise helpful assistant inside a messenger. " +
        "Do not claim access to the user's private Lumo chats, contacts, files, " +
        "location, microphone, camera, or account data unless that information " +
        "is explicitly included in this AI conversation."
    },
    ...history,
    { role: "user", content: userText }
  ];

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 25_000);
  timeout.unref?.();
  try {
    const response = await fetch(config.url, {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + config.apiKey,
        "Content-Type": "application/json",
        "Accept": "application/json"
      },
      body: JSON.stringify({
        model: config.model,
        messages,
        temperature: 0.6,
        max_tokens: 1200
      }),
      signal: controller.signal
    });
    if (!response.ok) {
      const error = new Error("AI provider request failed");
      error.code = response.status === 429 ? "AI_RATE_LIMITED" : "AI_PROVIDER_ERROR";
      throw error;
    }
    const json = await response.json();
    const content = json?.choices?.[0]?.message?.content;
    if (typeof content !== "string" || !content.trim()) {
      const error = new Error("Invalid AI provider response");
      error.code = "AI_PROVIDER_ERROR";
      throw error;
    }
    return content.trim().slice(0, MAX_PROVIDER_TEXT);
  } finally {
    clearTimeout(timeout);
  }
}
