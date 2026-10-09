#!/usr/bin/env node
/**
 * workbuddy bridge: a tiny protocol converter that lets Codex CLI (Responses
 * wire) and Claude Code (Anthropic Messages wire) talk to an OpenAI
 * chat-completions backend (workbuddy2api on the LAN).
 *
 * Listens on 127.0.0.1:7864. Routes:
 *   POST /v1/responses          -> chat/completions (Codex CLI)
 *   POST /v1/messages           -> chat/completions (Claude Code)
 * Everything else is proxied to the backend as-is.
 *
 * Mapping is intentionally minimal: system/user/assistant text, tool calls,
 * and tool results. Streaming is answered non-streamed (SSE emulated by one
 * final chunk) — both CLIs accept that.
 */
const http = require("http");

const BACKEND = process.env.WORKBUDDY_BASE || "http://192.168.1.197:7863";
const KEY = process.env.WORKBUDDY_API_KEY || "";
const PORT = parseInt(process.env.WORKBUDDY_BRIDGE_PORT || "7864", 10);

function post(path, bodyObj) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(bodyObj);
    const u = new URL(BACKEND + path);
    const req = http.request(
      { hostname: u.hostname, port: u.port, path: u.pathname, method: "POST",
        headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(data),
                   Authorization: "Bearer " + KEY } },
      (res) => {
        let buf = "";
        res.on("data", (c) => (buf += c));
        res.on("end", () => {
          try { resolve(JSON.parse(buf)); } catch (e) { reject(new Error("bad backend json: " + buf.slice(0, 200))); }
        });
      });
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

// ---- Responses (Codex) -> chat ----
function responsesToChat(b) {
  const msgs = [];
  let system = "";
  if (b.instructions) system += b.instructions + "\n";
  for (const item of b.input || []) {
    if (item.type === "message" || (!item.type && item.role)) {
      const role = item.role || "user";
      const c = typeof item.content === "string"
        ? item.content
        : (item.content || []).map((p) => (p.text || (p.type === "input_text" ? p.text : ""))).join("");
      if (role === "system" || role === "developer") system += c + "\n";
      else msgs.push({ role, content: c });
    } else if (item.type === "function_call") {
      msgs.push({ role: "assistant", tool_calls: [{ id: item.call_id || item.id, type: "function",
        function: { name: item.name, arguments: item.arguments || "{}" } }] });
    } else if (item.type === "function_call_output") {
      msgs.push({ role: "tool", tool_call_id: item.call_id, content: item.output || "" });
    } else if (item.type === "reasoning") {
      // no chat equivalent; drop
    }
  }
  const out = { model: b.model, messages: system ? [{ role: "system", content: system.trim() }, ...msgs] : msgs };
  if (b.max_output_tokens) out.max_tokens = b.max_output_tokens;
  if (b.tools) out.tools = b.tools.map((t) => ({ type: "function", function: {
    name: t.name, description: t.description || "", parameters: t.parameters || { type: "object", properties: {} } } }));
  if (b.tool_choice) out.tool_choice = b.tool_choice;
  return out;
}

function chatToResponses(d, model) {
  const m = d.choices[0];
  const out = { id: d.id || "resp_bridge", object: "response", created_at: d.created ? Math.floor(d.created) : Date.now() / 1000 | 0,
    model: model, status: "completed", output: [] };
  const rc = m.reasoning_content;
  if (rc) out.output.push({ type: "reasoning", id: "rs_bridge", summary: [] });
  if (m.tool_calls && m.tool_calls.length) {
    for (const tc of m.tool_calls) {
      out.output.push({ type: "function_call", id: "fc_" + (tc.id || Math.random().toString(36).slice(2)),
        call_id: tc.id, name: tc.function.name, arguments: tc.function.arguments, status: "completed" });
    }
  }
  if (m.content) out.output.push({ type: "message", id: "msg_bridge", role: "assistant", status: "completed",
    content: [{ type: "output_text", text: m.content, annotations: [] }] });
  out.usage = { input_tokens: (d.usage && d.usage.prompt_tokens) || 0, output_tokens: (d.usage && d.usage.completion_tokens) || 0,
    total_tokens: (d.usage && d.usage.total_tokens) || 0 };
  return out;
}

// ---- Anthropic Messages (Claude) -> chat ----
function messagesToChat(b) {
  const msgs = [];
  if (b.system) msgs.push({ role: "system", content: Array.isArray(b.system) ? b.system.map((p) => p.text || "").join("\n") : b.system });
  for (const m of b.messages || []) {
    const c = Array.isArray(m.content)
      ? m.content.map((p) => {
          if (p.type === "text") return p.text;
          if (p.type === "tool_use") return "";
          if (p.type === "tool_result") return typeof p.content === "string" ? p.content : (p.content || []).map((x) => x.text || "").join("");
          return "";
        }).filter(Boolean).join("\n")
      : m.content;
    msgs.push({ role: m.role, content: c });
  }
  const out = { model: b.model, messages: msgs, max_tokens: b.max_tokens || 1024 };
  if (b.tools) out.tools = b.tools.map((t) => ({ type: "function", function: {
    name: t.name, description: t.description || "", parameters: t.input_schema || { type: "object", properties: {} } } }));
  return out;
}

function chatToMessages(d, model) {
  const m = d.choices[0];
  const out = { id: d.id || "msg_bridge", type: "message", role: "assistant", model: model,
    content: [], stop_reason: null, stop_sequence: null,
    usage: { input_tokens: (d.usage && d.usage.prompt_tokens) || 0, output_tokens: (d.usage && d.usage.completion_tokens) || 0 } };
  if (m.tool_calls && m.tool_calls.length) {
    for (const tc of m.tool_calls) {
      let args = {};
      try { args = JSON.parse(tc.function.arguments || "{}"); } catch (e) {}
      out.content.push({ type: "tool_use", id: tc.id, name: tc.function.name, input: args });
    }
    out.stop_reason = "tool_use";
  }
  if (m.content) out.content.unshift({ type: "text", text: m.content });
  if (!out.stop_reason) out.stop_reason = "end_turn";
  return out;
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  let chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", async () => {
    const raw = Buffer.concat(chunks).toString("utf8");
    if (req.method === "GET" && req.url.startsWith("/v1/models")) {
      try {
        const models = await new Promise((resolve, reject) => {
          const u = new URL(BACKEND + "/v1/models");
          http.get({ hostname: u.hostname, port: u.port, path: u.pathname,
            headers: { Authorization: "Bearer " + KEY } }, (r) => {
            let b = ""; r.on("data", (c) => (b += c)); r.on("end", () => resolve(JSON.parse(b)));
          }).on("error", reject);
        });
        send(res, 200, models);
      } catch (e) { send(res, 502, { error: String(e.message || e) }); }
      return;
    }
    try {
      const body = raw ? JSON.parse(raw) : {};
      if (req.url.startsWith("/v1/responses")) {
        const d = await post("/v1/chat/completions", responsesToChat(body));
        send(res, 200, chatToResponses(d, body.model));
      } else if (req.url.startsWith("/v1/messages")) {
        const d = await post("/v1/chat/completions", messagesToChat(body));
        send(res, 200, chatToMessages(d, body.model));
      } else if (req.url.startsWith("/v1/chat/completions")) {
        // pass through with our key
        const d = await post("/v1/chat/completions", body);
        send(res, 200, d);
      } else {
        send(res, 404, { error: "unknown route " + req.url });
      }
    } catch (e) {
      send(res, 502, { error: String(e.message || e) });
    }
  });
});

server.listen(PORT, "127.0.0.1", () => console.log("[workbuddy-bridge] listening on 127.0.0.1:" + PORT));
