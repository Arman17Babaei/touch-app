package main

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"golang.org/x/net/websocket"
)

const (
	defaultRingTimeout      = 60 * time.Second
	defaultReconnectTimeout = 30 * time.Second
)

// LiveHub is deliberately ephemeral: durable recorded touches remain on /v1/touches.
type LiveHub struct {
	mu               sync.Mutex
	clients          map[string]*liveClient
	calls            map[string]*liveCall
	notifier         Notifier
	now              func() time.Time
	ringTimeout      time.Duration
	reconnectTimeout time.Duration
}

type liveClient struct {
	id        string
	username  string
	conn      *websocket.Conn
	send      chan []byte
	done      chan struct{}
	peerID    string
	streamID  string
	nextIndex int
}

type liveCall struct {
	id                string
	callerID          string
	callerUsername    string
	recipientID       string
	recipientUsername string
	expiresAt         time.Time
	reconnectDeadline time.Time
	accepted          bool
	generation        int
}

type liveEvent struct {
	Type              string `json:"type"`
	CallID            string `json:"callId,omitempty"`
	CallerUsername    string `json:"callerUsername,omitempty"`
	PeerUsername      string `json:"peerUsername,omitempty"`
	Reason            string `json:"reason,omitempty"`
	Generation        int    `json:"generation,omitempty"`
	StreamID          string `json:"streamId,omitempty"`
	RecipientUsername string `json:"recipientUsername,omitempty"`
	SamplePeriodMs    int    `json:"samplePeriodMs,omitempty"`
	StartIndex        *int   `json:"startIndex,omitempty"`
	Amplitudes        []int  `json:"amplitudes,omitempty"`
	Code              string `json:"code,omitempty"`
}

func NewLiveHub(notifier Notifier, now func() time.Time) *LiveHub {
	return &LiveHub{
		clients: map[string]*liveClient{}, calls: map[string]*liveCall{}, notifier: notifier, now: now,
		ringTimeout: defaultRingTimeout, reconnectTimeout: defaultReconnectTimeout,
	}
}

func (s *Server) live(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	installation, err := s.store.Installation(r.Context(), id)
	if err != nil {
		logAt(warnLevel, "live authentication rejected installation_id=%s reason=installation_not_registered error=%v", installationLogID(id), err)
		writeAPIError(w, http.StatusUnauthorized, errorInstallationNotRegistered, "installation is not registered")
		return
	}
	logAt(infoLevel, "live websocket upgrade installation_id=%s username=%s", installationLogID(installation.ID), installation.Handle)
	websocket.Server{
		Handler:   func(conn *websocket.Conn) { s.hub.connect(r.Context(), conn, installation, s.store) },
		Handshake: func(_ *websocket.Config, _ *http.Request) error { return nil },
	}.ServeHTTP(w, r)
}

func (h *LiveHub) connect(ctx context.Context, conn *websocket.Conn, installation Installation, store *Store) {
	client := &liveClient{id: installation.ID, username: installation.Handle, conn: conn, send: make(chan []byte, 32), done: make(chan struct{})}
	h.mu.Lock()
	if old := h.clients[client.id]; old != nil {
		_ = old.conn.Close()
	}
	h.clients[client.id] = client
	pending := make([]liveEvent, 0)
	for _, call := range h.calls {
		if call.recipientID == client.id && !call.accepted && h.now().Before(call.expiresAt) {
			pending = append(pending, liveEvent{Type: "incoming", CallID: call.id, CallerUsername: call.callerUsername})
		}
	}
	h.mu.Unlock()
	for _, event := range pending {
		h.reply(client, event)
	}

	defer func() {
		close(client.done)
		h.disconnect(client)
		_ = conn.Close()
	}()
	go func() {
		for {
			select {
			case raw := <-client.send:
				if websocket.Message.Send(conn, string(raw)) != nil {
					_ = conn.Close()
					return
				}
			case <-client.done:
				return
			}
		}
	}()

	for {
		var raw string
		if websocket.Message.Receive(conn, &raw) != nil {
			return
		}
		var event liveEvent
		if json.Unmarshal([]byte(raw), &event) != nil {
			h.reply(client, liveEvent{Type: "error", Code: "INVALID_EVENT"})
			continue
		}
		switch event.Type {
		case "call":
			h.startCall(ctx, client, event, store)
		case "accept":
			h.acceptCall(client, event.CallID)
		case "decline":
			h.finishCall(client, event.CallID, "declined")
		case "resume":
			h.resumeCall(client, event.CallID)
		case "hangup":
			h.finishCall(client, event.CallID, "hangup")
		default:
			h.handleLegacy(ctx, client, event, store)
		}
	}
}

func (h *LiveHub) startCall(ctx context.Context, client *liveClient, event liveEvent, store *Store) {
	if !uuidPattern.MatchString(event.CallID) || !handlePattern.MatchString(event.RecipientUsername) {
		h.reply(client, liveEvent{Type: "error", CallID: event.CallID, Code: "INVALID_CALL"})
		return
	}
	recipient, err := store.InstallationByUsername(ctx, event.RecipientUsername)
	if err != nil || recipient.ID == client.id {
		h.reply(client, liveEvent{Type: "error", CallID: event.CallID, Code: "RECIPIENT_UNAVAILABLE"})
		return
	}
	call := &liveCall{
		id: event.CallID, callerID: client.id, callerUsername: client.username,
		recipientID: recipient.ID, recipientUsername: recipient.Handle, expiresAt: h.now().Add(h.ringTimeout),
	}
	h.mu.Lock()
	if existing := h.calls[event.CallID]; existing != nil {
		existingCallerID, existingID, existingRecipient := existing.callerID, existing.id, existing.recipientUsername
		h.mu.Unlock()
		if existingCallerID != client.id {
			h.reply(client, liveEvent{Type: "error", CallID: event.CallID, Code: "INVALID_CALL"})
			return
		}
		h.reply(client, liveEvent{Type: "ringing", CallID: existingID, PeerUsername: existingRecipient})
		return
	}
	for _, active := range h.calls {
		callerBusy := active.callerID == client.id || active.recipientID == client.id
		recipientBusy := active.callerID == recipient.ID || active.recipientID == recipient.ID
		if callerBusy || recipientBusy {
			h.mu.Unlock()
			h.reply(client, liveEvent{Type: "error", CallID: event.CallID, Code: "CALL_BUSY"})
			return
		}
	}
	h.calls[call.id] = call
	recipientClient := h.clients[recipient.ID]
	h.mu.Unlock()

	h.reply(client, liveEvent{Type: "ringing", CallID: call.id, PeerUsername: call.recipientUsername})
	if recipientClient != nil {
		h.reply(recipientClient, liveEvent{Type: "incoming", CallID: call.id, CallerUsername: call.callerUsername})
	} else if err := h.notifier.NotifyLiveInvite(ctx, recipient.FCMToken, call.id, call.callerUsername); err != nil {
		logAt(errorLevel, "notify live call failed call_id=%s error=%v", call.id, err)
	}
	go h.expireCall(call.id, call.expiresAt, "unanswered")
}

func (h *LiveHub) acceptCall(client *liveClient, callID string) {
	h.mu.Lock()
	call := h.calls[callID]
	if call == nil || call.recipientID != client.id || !h.now().Before(call.expiresAt) {
		h.mu.Unlock()
		h.reply(client, liveEvent{Type: "error", CallID: callID, Code: "CALL_UNAVAILABLE"})
		return
	}
	call.accepted = true
	h.connectCallLocked(call)
	h.mu.Unlock()
}

func (h *LiveHub) resumeCall(client *liveClient, callID string) {
	h.mu.Lock()
	call := h.calls[callID]
	if call == nil || !call.accepted || (call.callerID != client.id && call.recipientID != client.id) || (!call.reconnectDeadline.IsZero() && !h.now().Before(call.reconnectDeadline)) {
		h.mu.Unlock()
		h.reply(client, liveEvent{Type: "error", CallID: callID, Code: "CALL_UNAVAILABLE"})
		return
	}
	h.connectCallLocked(call)
	h.mu.Unlock()
}

func (h *LiveHub) connectCallLocked(call *liveCall) {
	caller, recipient := h.clients[call.callerID], h.clients[call.recipientID]
	if caller == nil || recipient == nil {
		present, peer := caller, call.recipientUsername
		if present == nil {
			present, peer = recipient, call.callerUsername
		}
		if present != nil {
			h.reply(present, liveEvent{Type: "reconnecting", CallID: call.id, PeerUsername: peer})
		}
		return
	}
	call.reconnectDeadline = time.Time{}
	call.generation++
	h.reply(caller, liveEvent{Type: "connected", CallID: call.id, PeerUsername: call.recipientUsername, Generation: call.generation})
	h.reply(recipient, liveEvent{Type: "connected", CallID: call.id, PeerUsername: call.callerUsername, Generation: call.generation})
}

func (h *LiveHub) finishCall(client *liveClient, callID, reason string) {
	h.mu.Lock()
	call := h.calls[callID]
	if call == nil || (call.callerID != client.id && call.recipientID != client.id) {
		h.mu.Unlock()
		h.reply(client, liveEvent{Type: "error", CallID: callID, Code: "CALL_UNAVAILABLE"})
		return
	}
	delete(h.calls, callID)
	participants := []*liveClient{h.clients[call.callerID], h.clients[call.recipientID]}
	h.mu.Unlock()
	for _, participant := range participants {
		if participant != nil {
			h.reply(participant, liveEvent{Type: "ended", CallID: callID, Reason: reason})
		}
	}
}

func (h *LiveHub) disconnect(client *liveClient) {
	h.mu.Lock()
	if h.clients[client.id] != client {
		h.mu.Unlock()
		return
	}
	delete(h.clients, client.id)
	deadlines := make(map[string]time.Time)
	for _, call := range h.calls {
		if !call.accepted || (call.callerID != client.id && call.recipientID != client.id) {
			continue
		}
		call.reconnectDeadline = h.now().Add(h.reconnectTimeout)
		deadlines[call.id] = call.reconnectDeadline
		peerID, peerName := call.callerID, call.callerUsername
		if peerID == client.id {
			peerID, peerName = call.recipientID, call.recipientUsername
		}
		if peer := h.clients[peerID]; peer != nil {
			h.reply(peer, liveEvent{Type: "reconnecting", CallID: call.id, PeerUsername: peerName})
		}
	}
	h.mu.Unlock()
	for id, deadline := range deadlines {
		go h.expireReconnect(id, deadline)
	}
}

func (h *LiveHub) expireCall(callID string, deadline time.Time, reason string) {
	if wait := time.Until(deadline); wait > 0 {
		time.Sleep(wait)
	}
	h.mu.Lock()
	call := h.calls[callID]
	if call == nil || call.accepted || h.now().Before(deadline) {
		h.mu.Unlock()
		return
	}
	delete(h.calls, callID)
	participants := []*liveClient{h.clients[call.callerID], h.clients[call.recipientID]}
	h.mu.Unlock()
	for _, participant := range participants {
		if participant != nil {
			h.reply(participant, liveEvent{Type: "ended", CallID: callID, Reason: reason})
		}
	}
}

func (h *LiveHub) expireReconnect(callID string, deadline time.Time) {
	if wait := time.Until(deadline); wait > 0 {
		time.Sleep(wait)
	}
	h.mu.Lock()
	call := h.calls[callID]
	if call == nil || call.reconnectDeadline != deadline || h.now().Before(deadline) {
		h.mu.Unlock()
		return
	}
	delete(h.calls, callID)
	participants := []*liveClient{h.clients[call.callerID], h.clients[call.recipientID]}
	h.mu.Unlock()
	for _, participant := range participants {
		if participant != nil {
			h.reply(participant, liveEvent{Type: "ended", CallID: callID, Reason: "connection_lost"})
		}
	}
}

// handleLegacy preserves the v1 start/samples/end relay for already released clients.
func (h *LiveHub) handleLegacy(ctx context.Context, client *liveClient, event liveEvent, store *Store) {
	if event.Type == "start" {
		recipient, err := store.InstallationByUsername(ctx, event.RecipientUsername)
		if err != nil || !uuidPattern.MatchString(event.StreamID) || event.SamplePeriodMs < 1 || event.SamplePeriodMs > 100 {
			h.reply(client, liveEvent{Type: "error", Code: "INVALID_START"})
			return
		}
		client.peerID, client.streamID, client.nextIndex = recipient.ID, event.StreamID, 0
	}
	if client.peerID == "" || (event.Type != "start" && event.Type != "samples" && event.Type != "end") {
		h.reply(client, liveEvent{Type: "error", Code: "NO_ACTIVE_STREAM"})
		return
	}
	if event.Type != "start" && event.StreamID != client.streamID {
		h.reply(client, liveEvent{Type: "error", Code: "NO_ACTIVE_STREAM"})
		return
	}
	if event.Type == "samples" && (len(event.Amplitudes) == 0 || len(event.Amplitudes) > 100 || event.StartIndex == nil || *event.StartIndex != client.nextIndex || !validAmplitudes(event.Amplitudes)) {
		h.reply(client, liveEvent{Type: "error", Code: "INVALID_SAMPLES"})
		return
	}
	if event.Type == "samples" {
		client.nextIndex += len(event.Amplitudes)
	}
	encoded, _ := json.Marshal(event)
	h.mu.Lock()
	peer := h.clients[client.peerID]
	h.mu.Unlock()
	if peer != nil {
		h.send(peer, encoded)
	} else {
		h.reply(client, liveEvent{Type: "peerUnavailable"})
	}
	if event.Type == "end" {
		client.peerID, client.streamID = "", ""
	}
}

func validAmplitudes(values []int) bool {
	for _, value := range values {
		if value < 0 || value > 255 {
			return false
		}
	}
	return true
}

func (h *LiveHub) reply(client *liveClient, event liveEvent) {
	raw, _ := json.Marshal(event)
	h.send(client, raw)
}

func (h *LiveHub) send(client *liveClient, raw []byte) {
	select {
	case <-client.done:
		return
	default:
	}
	select {
	case client.send <- raw:
	case <-client.done:
	default:
	}
}
