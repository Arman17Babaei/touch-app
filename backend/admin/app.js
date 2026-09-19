const $ = (id) => document.getElementById(id);
const state = { timer: null, clients: [], calls: [], streams: [] };

const when = (value) => value ? new Date(Number(value)).toLocaleString() : "–";
const clean = (value) => String(value ?? "");
function statusPill(value) {
  const node = document.createElement("span");
  node.className = `pill ${clean(value).toLowerCase()}`;
  node.textContent = clean(value || "unknown");
  return node;
}
function setLoginStatus(message = "") { $("login-status").textContent = message; }
function token() { return $("token").value.trim(); }

async function api(url) {
  if (!token()) throw new Error("Enter the admin token.");
  const response = await fetch(url, { headers: { Authorization: `Bearer ${token()}`, Accept: "application/json" }, cache: "no-store" });
  if (!response.ok) {
    let message = "";
    try { message = (await response.json()).message || ""; } catch (_) { /* HTTP status is enough */ }
    throw new Error(`${response.status}${message ? `: ${message}` : ""}`);
  }
  return response.json();
}
function empty(target) {
  const node = document.createElement("div");
  node.className = "empty";
  node.textContent = "No matching records.";
  $(target).replaceChildren(node);
}
function table(target, headers, rows) {
  if (!rows.length) return empty(target);
  const table = document.createElement("table");
  const head = document.createElement("thead"), headRow = document.createElement("tr");
  headers.forEach((name) => { const th = document.createElement("th"); th.textContent = name; headRow.append(th); });
  head.append(headRow); table.append(head);
  const body = document.createElement("tbody");
  rows.forEach(({ cells, onClick }) => {
    const row = document.createElement("tr");
    if (onClick) { row.className = "click"; row.onclick = onClick; }
    cells.forEach(({ text, node, className }) => { const cell = document.createElement("td"); if (node) cell.append(node); else cell.textContent = clean(text); if (className) cell.className = className; row.append(cell); });
    body.append(row);
  });
  table.append(body); $(target).replaceChildren(table);
}
function setOptions(id, items, value, label) {
  const select = $(id), current = select.value;
  select.replaceChildren(new Option(label, ""));
  items.forEach((item) => select.append(new Option(item.label, item.value)));
  select.value = items.some((item) => item.value === current) ? current : "";
}
function diagnosticsQuery() {
  const query = new URLSearchParams({ limit: $("limit").value });
  [["installationId", "installation"], ["callId", "call"], ["severity", "severity"], ["messageId", "message"]].forEach(([key, id]) => { if ($(id).value.trim()) query.set(key, $(id).value.trim()); });
  const minutes = Number($("since").value);
  if (minutes) query.set("sinceMs", String(Date.now() - minutes * 60_000));
  return query;
}
function renderClients(clients) {
  $("client-count").textContent = `${clients.filter((client) => client.connected).length}/${clients.length}`;
  const container = $("client-list");
  if (!clients.length) return empty("client-list");
  container.replaceChildren(...clients.map((client) => {
    const row = document.createElement("div"); row.className = "client";
    row.onclick = () => { $("installation").value = client.installationId; load(); };
    const dot = document.createElement("span"); dot.className = `dot ${client.connected ? "online" : ""}`;
    const text = document.createElement("div"); const title = document.createElement("div"); title.className = "client-name"; title.textContent = client.username;
    const meta = document.createElement("div"); meta.className = "client-meta"; meta.textContent = `${client.platform} · ${client.connected ? "socket connected" : "offline"}${client.activeCallId ? ` · ${client.callState}` : ""}`;
    text.append(title, meta); row.append(dot, text, statusPill(client.connected ? "connected" : "offline")); return row;
  }));
}
function renderStreams(streams) {
  $("open-count").textContent = streams.length;
  const container = $("stream-list");
  if (!streams.length) return empty("stream-list");
  container.replaceChildren(...streams.map((stream) => {
    const row = document.createElement("div"); row.className = "stream"; row.onclick = () => showTimeline("call", stream.callId);
    const top = document.createElement("div"); top.className = "stream-top"; const peers = document.createElement("strong"); peers.textContent = `${stream.callerUsername} → ${stream.recipientUsername}`; top.append(peers, statusPill(stream.state));
    const meta = document.createElement("div"); meta.className = "stream-meta"; meta.textContent = `generation ${stream.generation}${stream.reconnectUntilMs ? ` · reconnect deadline ${when(stream.reconnectUntilMs)}` : ""}`;
    row.append(top, meta); return row;
  }));
}
function render(data) {
  const clients = data.clientData.clients || [], streams = data.clientData.streams || [], calls = data.calls.calls || [], notifications = data.notifications.notifications || [], events = data.diagnostics.events || [];
  state.clients = clients; state.streams = streams; state.calls = calls;
  setOptions("installation", clients.map((client) => ({ value: client.installationId, label: `${client.username} (${client.platform}${client.connected ? ", connected" : ""})` })), "", "All clients");
  setOptions("call", [...streams, ...calls].filter((call, index, all) => all.findIndex((x) => x.callId === call.callId) === index).map((call) => ({ value: call.callId, label: `${call.callerUsername} → ${call.recipientUsername} · ${call.state}` })), "", "All calls");
  renderClients(clients); renderStreams(streams);
  $("connected-count").textContent = clients.filter((client) => client.connected).length;
  $("client-summary").textContent = `${clients.length} registered installations`;
  $("streams-count").textContent = streams.length;
  $("stream-summary").textContent = streams.filter((stream) => stream.state === "reconnecting").length ? `${streams.filter((stream) => stream.state === "reconnecting").length} reconnecting` : "no reconnects";
  $("notifications-count").textContent = notifications.length;
  $("errors-count").textContent = events.filter((event) => ["error", "fatal"].includes(event.severity)).length;
  $("diagnostic-summary").textContent = `${events.length} event${events.length === 1 ? "" : "s"}`;
  table("diagnostics", ["Time", "Severity", "Client", "Event", "Message", "Call"], events.map((event) => ({ cells: [{ text: when(event.occurredAtMs) }, { node: statusPill(event.severity) }, { text: event.installationId, className: "mono" }, { text: `${event.category}/${event.name}` }, { text: event.message }, { text: event.callId, className: "mono" }], onClick: event.callId ? () => showTimeline("call", event.callId) : null })));
  table("calls", ["Created", "Peers", "State", "Haptic / audio / drops", "Reason"], calls.map((call) => ({ cells: [{ text: when(call.createdAtMs) }, { text: `${call.callerUsername} → ${call.recipientUsername}` }, { node: statusPill(call.state) }, { text: `${call.hapticFrames} / ${call.audioFrames} / ${call.queueDrops}` }, { text: call.reason || "–" }], onClick: () => showTimeline("call", call.callId) })));
  table("notifications", ["Created", "Kind", "Status", "Reference", "Provider / error"], notifications.map((delivery) => ({ cells: [{ text: when(delivery.createdAtMs) }, { text: delivery.kind }, { node: statusPill(delivery.status) }, { text: delivery.referenceId, className: "mono" }, { text: delivery.error || delivery.providerMessageId || "–" }], onClick: () => showTimeline("notification", delivery.deliveryId) })));
}
async function showTimeline(kind, id) {
  try {
    $("timeline-title").textContent = kind === "call" ? "Call timeline" : "Delivery timeline";
    $("timeline").textContent = "Loading timeline…";
    const data = await api(`/v1/admin/${kind}s/${encodeURIComponent(id)}`);
    const timeline = $("timeline"); timeline.classList.remove("empty"); timeline.replaceChildren();
    (data.events || []).forEach((event) => {
      const item = document.createElement("article"); item.className = "timeline-item";
      const title = document.createElement("strong"); title.textContent = event.event || "event";
      const time = document.createElement("div"); time.className = "timeline-time"; time.textContent = `${when(event.occurredAtMs)}${event.installationId ? ` · ${event.installationId}` : ""}`;
      item.append(title, time);
      if (event.details && Object.keys(event.details).length) { const details = document.createElement("pre"); details.textContent = JSON.stringify(event.details, null, 2); item.append(details); }
      timeline.append(item);
    });
    if (!timeline.children.length) { timeline.classList.add("empty"); timeline.textContent = "No timeline events."; }
  } catch (error) { $("timeline").classList.add("empty"); $("timeline").textContent = `Timeline failed: ${error.message}`; }
}
async function load() {
  try {
    sessionStorage.setItem("touchAdminToken", token());
    $("updated").textContent = "Loading…";
    const query = diagnosticsQuery();
    const [clientData, calls, notifications, diagnostics] = await Promise.all([api("/v1/admin/clients"), api(`/v1/admin/calls?limit=${$("limit").value}`), api(`/v1/admin/notifications?limit=${$("limit").value}`), api(`/v1/admin/diagnostics?${query}`)]);
    render({ clientData, calls, notifications, diagnostics });
    $("login").hidden = true; $("console").hidden = false; $("updated").textContent = `Updated ${new Date().toLocaleTimeString()}`; setLoginStatus();
  } catch (error) { setLoginStatus(`Could not load admin data: ${error.message}`); $("updated").textContent = "Not connected"; }
}
function schedule() { clearInterval(state.timer); state.timer = $("auto-refresh").checked ? setInterval(load, 5000) : null; }
$("token").value = sessionStorage.getItem("touchAdminToken") || "";
$("connect").onclick = load; $("refresh-now").onclick = load; $("apply-filters").onclick = load; $("auto-refresh").onchange = schedule;
$("forget").onclick = () => { $("token").value = ""; sessionStorage.removeItem("touchAdminToken"); $("console").hidden = true; $("login").hidden = false; setLoginStatus("Token forgotten."); };
$("clear-filters").onclick = () => { ["installation", "call", "severity", "since", "message"].forEach((id) => { $(id).value = ""; }); load(); };
$("clear-detail").onclick = () => { $("timeline-title").textContent = "Timeline"; $("timeline").className = "timeline empty"; $("timeline").textContent = "Select a call, stream, or notification."; };
if (token()) load();
