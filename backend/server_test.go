package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

const (
	phoneID   = "11111111-1111-4111-8111-111111111111"
	watchID   = "22222222-2222-4222-8222-222222222222"
	messageID = "33333333-3333-4333-8333-333333333333"
	secondID  = "44444444-4444-4444-8444-444444444444"
)

type fakeNotifier struct {
	mu          sync.Mutex
	tokens      []string
	touchIDs    []string
	liveCallIDs []string
	liveCallers []string
	err         error
}

func (n *fakeNotifier) NotifyTouch(_ context.Context, token, touchID string) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.tokens = append(n.tokens, token)
	n.touchIDs = append(n.touchIDs, touchID)
	return n.err
}

func (n *fakeNotifier) NotifyLiveInvite(_ context.Context, token, callID, callerUsername string) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.tokens = append(n.tokens, token)
	n.liveCallIDs = append(n.liveCallIDs, callID)
	n.liveCallers = append(n.liveCallers, callerUsername)
	return n.err
}

func (n *fakeNotifier) snapshot() (tokens, touchIDs, liveCallIDs, liveCallers []string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	return append([]string(nil), n.tokens...), append([]string(nil), n.touchIDs...), append([]string(nil), n.liveCallIDs...), append([]string(nil), n.liveCallers...)
}

func (n *fakeNotifier) setError(err error) { n.mu.Lock(); n.err = err; n.mu.Unlock() }

func testServer(t *testing.T) (*Server, *Store, *fakeNotifier) {
	t.Helper()
	store, err := OpenStore(filepath.Join(t.TempDir(), "touch.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	notifier := &fakeNotifier{}
	server := NewServer(store, notifier)
	server.now = func() time.Time { return time.Unix(1_700_000_000, 0).UTC() }
	return server, store, notifier
}

func request(t *testing.T, handler http.Handler, method, path, installationID string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var payload bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&payload).Encode(body); err != nil {
			t.Fatal(err)
		}
	}
	req := httptest.NewRequest(method, path, &payload)
	if installationID != "" {
		req.Header.Set("X-Installation-ID", installationID)
	}
	result := httptest.NewRecorder()
	handler.ServeHTTP(result, req)
	return result
}

func register(t *testing.T, handler http.Handler, id, handle, platform, token string) *httptest.ResponseRecorder {
	return request(t, handler, http.MethodPut, "/v1/installations/"+id, id, map[string]any{
		"username": handle, "platform": platform, "fcmToken": token,
	})
}

func TestRegistrationAndHandleConflict(t *testing.T) {
	server, _, _ := testServer(t)
	handler := server.Handler()
	if result := register(t, handler, phoneID, "Arman.Phone", "phone", "phone-token"); result.Code != http.StatusOK {
		t.Fatalf("register phone: %d %s", result.Code, result.Body.String())
	}
	if result := register(t, handler, watchID, "arman.phone", "watch", "watch-token"); result.Code != http.StatusConflict {
		t.Fatalf("expected conflict, got %d %s", result.Code, result.Body.String())
	}
}

func TestSendIdempotencyFIFOAndAck(t *testing.T) {
	server, _, notifier := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "phone-token")
	register(t, handler, watchID, "watch", "watch", "watch-token")
	body := map[string]any{
		"clientMessageId":   messageID,
		"recipientUsername": "WATCH",
		"samplePeriodMs":    10,
		"amplitudes":        []int{0, 32, 128, 255, 0},
	}
	first := request(t, handler, http.MethodPost, "/v1/touches", phoneID, body)
	if first.Code != http.StatusAccepted {
		t.Fatalf("send: %d %s", first.Code, first.Body.String())
	}
	duplicate := request(t, handler, http.MethodPost, "/v1/touches", phoneID, body)
	if duplicate.Code != http.StatusAccepted {
		t.Fatalf("duplicate: %d %s", duplicate.Code, duplicate.Body.String())
	}
	tokens, touchIDs, _, _ := notifier.snapshot()
	if len(touchIDs) != 1 || tokens[0] != "watch-token" {
		t.Fatalf("unexpected notifications: %#v %#v", touchIDs, tokens)
	}
	server.now = func() time.Time { return time.Unix(1_700_000_001, 0).UTC() }
	second := request(t, handler, http.MethodPost, "/v1/touches", phoneID, map[string]any{
		"clientMessageId": secondID, "recipientUsername": "watch", "samplePeriodMs": 20, "amplitudes": []int{64},
	})
	if second.Code != http.StatusAccepted {
		t.Fatalf("second send: %d %s", second.Code, second.Body.String())
	}

	inbox := request(t, handler, http.MethodGet, "/v1/touches?state=pending", watchID, nil)
	if inbox.Code != http.StatusOK {
		t.Fatalf("inbox: %d %s", inbox.Code, inbox.Body.String())
	}
	var response struct {
		Touches []pendingTouchResponse `json:"touches"`
	}
	if err := json.Unmarshal(inbox.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if len(response.Touches) != 2 || response.Touches[0].ClientMessageID != messageID || response.Touches[1].ClientMessageID != secondID {
		t.Fatalf("unexpected inbox: %#v", response.Touches)
	}
	touchID := response.Touches[0].TouchID
	ack := request(t, handler, http.MethodPost, "/v1/touches/"+touchID+"/ack", watchID, map[string]string{"state": "persisted"})
	if ack.Code != http.StatusNoContent {
		t.Fatalf("ack: %d %s", ack.Code, ack.Body.String())
	}
	remaining := request(t, handler, http.MethodGet, "/v1/touches?state=pending", watchID, nil)
	if remaining.Code != http.StatusOK || !bytes.Contains(remaining.Body.Bytes(), []byte(secondID)) {
		t.Fatalf("expected second FIFO item to remain, got %d %s", remaining.Code, remaining.Body.String())
	}
	secondAck := request(t, handler, http.MethodPost, "/v1/touches/"+response.Touches[1].TouchID+"/ack", watchID, map[string]string{"state": "played"})
	if secondAck.Code != http.StatusNoContent {
		t.Fatalf("second ack: %d %s", secondAck.Code, secondAck.Body.String())
	}
	if result := request(t, handler, http.MethodGet, "/v1/touches?state=pending", watchID, nil); result.Code != http.StatusOK || result.Body.String() != "{\"touches\":[]}\n" {
		t.Fatalf("expected empty inbox, got %d %s", result.Code, result.Body.String())
	}
}

func TestTouchValidationAndMissingRecipient(t *testing.T) {
	server, _, _ := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "")
	tests := []struct {
		name string
		body map[string]any
		want int
	}{
		{"silent", map[string]any{"clientMessageId": messageID, "recipientUsername": "nobody", "samplePeriodMs": 10, "amplitudes": []int{0, 0}}, http.StatusBadRequest},
		{"bad amplitude", map[string]any{"clientMessageId": messageID, "recipientUsername": "nobody", "samplePeriodMs": 10, "amplitudes": []int{256}}, http.StatusBadRequest},
		{"bad period", map[string]any{"clientMessageId": messageID, "recipientUsername": "nobody", "samplePeriodMs": 101, "amplitudes": []int{1}}, http.StatusBadRequest},
		{"too many samples", map[string]any{"clientMessageId": messageID, "recipientUsername": "nobody", "samplePeriodMs": 10, "amplitudes": make([]int, 3001)}, http.StatusBadRequest},
		{"missing recipient", map[string]any{"clientMessageId": messageID, "recipientUsername": "nobody", "samplePeriodMs": 10, "amplitudes": []int{100}}, http.StatusNotFound},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			result := request(t, handler, http.MethodPost, "/v1/touches", phoneID, test.body)
			if result.Code != test.want {
				t.Fatalf("got %d, want %d: %s", result.Code, test.want, result.Body.String())
			}
		})
	}
}

func TestFCMFailureDoesNotLoseCommittedTouch(t *testing.T) {
	server, _, notifier := testServer(t)
	notifier.setError(errors.New("firebase unavailable"))
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "phone-token")
	register(t, handler, watchID, "watch", "watch", "watch-token")
	result := request(t, handler, http.MethodPost, "/v1/touches", phoneID, map[string]any{
		"clientMessageId": messageID, "recipientUsername": "watch", "samplePeriodMs": 10, "amplitudes": []int{80},
	})
	if result.Code != http.StatusAccepted {
		t.Fatalf("send with failed FCM: %d %s", result.Code, result.Body.String())
	}
	inbox := request(t, handler, http.MethodGet, "/v1/touches?state=pending", watchID, nil)
	var response struct {
		Touches []pendingTouchResponse `json:"touches"`
	}
	if err := json.Unmarshal(inbox.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if len(response.Touches) != 1 {
		t.Fatalf("committed touch was lost after FCM failure: %s", inbox.Body.String())
	}
}

func TestExpiredMessagesAreNotDelivered(t *testing.T) {
	server, store, _ := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "")
	register(t, handler, watchID, "watch", "watch", "")
	request(t, handler, http.MethodPost, "/v1/touches", phoneID, map[string]any{
		"clientMessageId": messageID, "recipientUsername": "watch", "samplePeriodMs": 10, "amplitudes": []int{80},
	})
	if _, err := store.db.Exec(`UPDATE touches SET expires_at = ?`, server.now().Add(-time.Second).UnixMilli()); err != nil {
		t.Fatal(err)
	}
	result := request(t, handler, http.MethodGet, "/v1/touches?state=pending", watchID, nil)
	if result.Body.String() != "{\"touches\":[]}\n" {
		t.Fatalf("expired touch returned: %s", result.Body.String())
	}
}
