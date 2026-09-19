package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func contains(value, part string) bool { return strings.Contains(value, part) }
func decodeTestJSON(t *testing.T, result *httptest.ResponseRecorder, out any) {
	t.Helper()
	if err := json.Unmarshal(result.Body.Bytes(), out); err != nil {
		t.Fatal(err)
	}
}
func newAdminRequest(t *testing.T, method, path, token string) *http.Request {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	return req
}
func httptestResult(handler http.Handler, req *http.Request) *httptest.ResponseRecorder {
	result := httptest.NewRecorder()
	handler.ServeHTTP(result, req)
	return result
}

func TestContactsInstallationStatusAndPushVerification(t *testing.T) {
	server, _, _ := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "phone-token")
	register(t, handler, watchID, "watch", "watch", "watch-token")
	if got := request(t, handler, http.MethodPost, "/v1/contacts", phoneID, map[string]any{"username": "WATCH"}); got.Code != http.StatusCreated {
		t.Fatalf("add contact: %d %s", got.Code, got.Body.String())
	}
	if got := request(t, handler, http.MethodGet, "/v1/contacts", phoneID, nil); got.Code != http.StatusOK || !contains(got.Body.String(), "watch") {
		t.Fatalf("contacts: %d %s", got.Code, got.Body.String())
	}
	if got := request(t, handler, http.MethodGet, "/v1/installations/"+phoneID, phoneID, nil); got.Code != http.StatusOK || !contains(got.Body.String(), "fcmTokenFingerprint") {
		t.Fatalf("status: %d %s", got.Code, got.Body.String())
	}
	started := request(t, handler, http.MethodPost, "/v1/installations/"+phoneID+"/push-tests", phoneID, map[string]any{})
	if started.Code != http.StatusAccepted {
		t.Fatalf("push: %d %s", started.Code, started.Body.String())
	}
	var body pushTestResponse
	decodeTestJSON(t, started, &body)
	if got := request(t, handler, http.MethodPost, "/v1/push-tests/"+body.TestID+"/ack", phoneID, map[string]any{}); got.Code != http.StatusOK || !contains(got.Body.String(), "delivered") {
		t.Fatalf("ack push: %d %s", got.Code, got.Body.String())
	}
}

func TestDiagnosticsRequireAdminToken(t *testing.T) {
	server, _, _ := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "")
	eventID := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	got := request(t, handler, http.MethodPost, "/v1/diagnostics/events", phoneID, map[string]any{"events": []any{map[string]any{"eventId": eventID, "occurredAtMs": server.now().UnixMilli(), "severity": "warn", "category": "call", "name": "socket_failure", "message": "safe"}}})
	if got.Code != http.StatusNoContent {
		t.Fatalf("diagnostic: %d %s", got.Code, got.Body.String())
	}
	t.Setenv("TOUCH_ADMIN_TOKEN", "secret")
	unauthorized := request(t, handler, http.MethodGet, "/v1/admin/diagnostics", "", nil)
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("admin without token: %d", unauthorized.Code)
	}
	req := newAdminRequest(t, http.MethodGet, "/v1/admin/diagnostics", "secret")
	result := httptestResult(handler, req)
	if result.Code != http.StatusOK || !contains(result.Body.String(), "socket_failure") {
		t.Fatalf("admin: %d %s", result.Code, result.Body.String())
	}
}

func TestAdminClientsIncludesRegisteredAndLiveSnapshot(t *testing.T) {
	server, _, _ := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "phone-token")
	register(t, handler, watchID, "watch", "watch", "")
	server.hub.mu.Lock()
	server.hub.clients[phoneID] = &liveClient{id: phoneID, username: "phone", send: make(chan []byte, 2), control: make(chan []byte, 1)}
	server.hub.calls[messageID] = &liveCall{id: messageID, callerID: phoneID, callerUsername: "phone", recipientID: watchID, recipientUsername: "watch", accepted: true, generation: 3}
	server.hub.mu.Unlock()
	t.Setenv("TOUCH_ADMIN_TOKEN", "secret")
	result := httptestResult(handler, newAdminRequest(t, http.MethodGet, "/v1/admin/clients", "secret"))
	if result.Code != http.StatusOK || !contains(result.Body.String(), `"connected":true`) || !contains(result.Body.String(), `"generation":3`) || contains(result.Body.String(), "phone-token") {
		t.Fatalf("clients: %d %s", result.Code, result.Body.String())
	}
}

func TestNotificationTimelineAndSenderStatus(t *testing.T) {
	server, store, _ := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "")
	register(t, handler, watchID, "watch", "watch", "token")
	sent := request(t, handler, http.MethodPost, "/v1/touches", phoneID, map[string]any{"clientMessageId": messageID, "recipientUsername": "watch", "samplePeriodMs": 10, "amplitudes": []int{1}})
	if sent.Code != http.StatusAccepted {
		t.Fatal(sent.Body.String())
	}
	var accepted touchAcceptedResponse
	decodeTestJSON(t, sent, &accepted)
	var delivery string
	if err := store.db.QueryRow(`SELECT id FROM notification_deliveries WHERE reference_id=?`, accepted.TouchID).Scan(&delivery); err != nil {
		t.Fatal(err)
	}
	if got := request(t, handler, http.MethodPost, "/v1/notifications/"+delivery+"/events", watchID, map[string]any{"event": "received", "occurredAtMs": server.now().UnixMilli()}); got.Code != http.StatusNoContent {
		t.Fatalf("event: %d %s", got.Code, got.Body.String())
	}
	if got := request(t, handler, http.MethodGet, "/v1/touches/"+accepted.TouchID+"/status", phoneID, nil); got.Code != http.StatusOK || !contains(got.Body.String(), "notificationReceivedAtMs") {
		t.Fatalf("status: %d %s", got.Code, got.Body.String())
	}
	t.Setenv("TOUCH_ADMIN_TOKEN", "secret")
	timeline := httptestResult(handler, newAdminRequest(t, "GET", "/v1/admin/notifications/"+delivery, "secret"))
	if timeline.Code != http.StatusOK || !contains(timeline.Body.String(), "received") {
		t.Fatalf("timeline: %d %s", timeline.Code, timeline.Body.String())
	}
}
