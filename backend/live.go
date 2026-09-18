package main

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"

	"golang.org/x/net/websocket"
)

// LiveHub is deliberately ephemeral: durable recorded touches remain on /v1/touches.
type LiveHub struct {
	mu      sync.Mutex
	clients map[string]*liveClient
}
type liveClient struct {
	id        string
	conn      *websocket.Conn
	send      chan []byte
	done      chan struct{}
	peerID    string
	streamID  string
	nextIndex int
}
type liveEvent struct {
	Type              string `json:"type"`
	StreamID          string `json:"streamId,omitempty"`
	RecipientUsername string `json:"recipientUsername,omitempty"`
	SamplePeriodMs    int    `json:"samplePeriodMs,omitempty"`
	StartIndex        *int   `json:"startIndex,omitempty"`
	Amplitudes        []int  `json:"amplitudes,omitempty"`
	Code              string `json:"code,omitempty"`
}

func NewLiveHub() *LiveHub { return &LiveHub{clients: map[string]*liveClient{}} }

func (s *Server) live(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	if _, err := s.store.Installation(r.Context(), id); err != nil {
		writeAPIError(w, http.StatusUnauthorized, errorInstallationNotRegistered, "installation is not registered")
		return
	}
	// Native Android clients do not send a browser Origin. Authentication above is the v1 trust boundary.
	websocket.Server{
		Handler:   func(conn *websocket.Conn) { s.hub.connect(r.Context(), conn, id, s.store) },
		Handshake: func(_ *websocket.Config, _ *http.Request) error { return nil },
	}.ServeHTTP(w, r)
}

func (h *LiveHub) connect(ctx context.Context, conn *websocket.Conn, id string, store *Store) {
	client := &liveClient{id: id, conn: conn, send: make(chan []byte, 32), done: make(chan struct{})}
	h.mu.Lock()
	if old := h.clients[id]; old != nil {
		_ = old.conn.Close()
	}
	h.clients[id] = client
	h.mu.Unlock()
	defer func() {
		close(client.done)
		h.mu.Lock()
		if h.clients[id] == client {
			delete(h.clients, id)
		}
		h.mu.Unlock()
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
		if event.Type == "start" {
			recipient, err := store.InstallationByUsername(ctx, event.RecipientUsername)
			if err != nil || !uuidPattern.MatchString(event.StreamID) || event.SamplePeriodMs < 1 || event.SamplePeriodMs > 100 {
				h.reply(client, liveEvent{Type: "error", Code: "INVALID_START"})
				continue
			}
			client.peerID = recipient.ID
			client.streamID = event.StreamID
			client.nextIndex = 0
		}
		if client.peerID == "" || (event.Type != "start" && event.Type != "samples" && event.Type != "end") {
			h.reply(client, liveEvent{Type: "error", Code: "NO_ACTIVE_STREAM"})
			continue
		}
		if event.Type != "start" && event.StreamID != client.streamID {
			h.reply(client, liveEvent{Type: "error", Code: "NO_ACTIVE_STREAM"})
			continue
		}
		if event.Type == "samples" && (len(event.Amplitudes) == 0 || len(event.Amplitudes) > 100 || event.StartIndex == nil || *event.StartIndex != client.nextIndex || !validAmplitudes(event.Amplitudes)) {
			h.reply(client, liveEvent{Type: "error", Code: "INVALID_SAMPLES"})
			continue
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
			client.peerID = ""
			client.streamID = ""
		}
	}
}
func validAmplitudes(values []int) bool {
	for _, v := range values {
		if v < 0 || v > 255 {
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
