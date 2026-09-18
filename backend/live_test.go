package main

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/websocket"
)

func TestLiveRelayIsBidirectionalAndPreservesZeroStartIndex(t *testing.T) {
	server, _, _ := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "")
	register(t, handler, watchID, "watch", "watch", "")
	httpServer := httptest.NewServer(handler)
	defer httpServer.Close()

	connect := func(id string) *websocket.Conn {
		t.Helper()
		config, err := websocket.NewConfig("ws"+strings.TrimPrefix(httpServer.URL, "http")+"/v1/live", httpServer.URL)
		if err != nil {
			t.Fatal(err)
		}
		config.Header.Set("X-Installation-ID", id)
		conn, err := websocket.DialConfig(config)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = conn.Close() })
		return conn
	}
	receive := func(conn *websocket.Conn) liveEvent {
		t.Helper()
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		var raw string
		if err := websocket.Message.Receive(conn, &raw); err != nil {
			t.Fatal(err)
		}
		var event liveEvent
		if err := json.Unmarshal([]byte(raw), &event); err != nil {
			t.Fatal(err)
		}
		return event
	}
	send := func(conn *websocket.Conn, event liveEvent) {
		t.Helper()
		raw, err := json.Marshal(event)
		if err != nil {
			t.Fatal(err)
		}
		if err := websocket.Message.Send(conn, string(raw)); err != nil {
			t.Fatal(err)
		}
	}

	phone := connect(phoneID)
	watch := connect(watchID)
	phoneStream := "55555555-5555-4555-8555-555555555555"
	watchStream := "66666666-6666-4666-8666-666666666666"
	send(phone, liveEvent{Type: "start", StreamID: phoneStream, RecipientUsername: "watch", SamplePeriodMs: 10})
	if got := receive(watch); got.Type != "start" || got.StreamID != phoneStream {
		t.Fatalf("watch received %#v", got)
	}
	send(watch, liveEvent{Type: "start", StreamID: watchStream, RecipientUsername: "phone", SamplePeriodMs: 20})
	if got := receive(phone); got.Type != "start" || got.StreamID != watchStream {
		t.Fatalf("phone received %#v", got)
	}

	zero := 0
	send(phone, liveEvent{Type: "samples", StreamID: phoneStream, StartIndex: &zero, Amplitudes: []int{32, 128, 255}})
	if got := receive(watch); got.Type != "samples" || got.StartIndex == nil || *got.StartIndex != 0 || len(got.Amplitudes) != 3 {
		t.Fatalf("watch sample batch %#v", got)
	}
	send(watch, liveEvent{Type: "samples", StreamID: watchStream, StartIndex: &zero, Amplitudes: []int{255, 96}})
	if got := receive(phone); got.Type != "samples" || got.StartIndex == nil || *got.StartIndex != 0 || len(got.Amplitudes) != 2 {
		t.Fatalf("phone sample batch %#v", got)
	}
}

func TestCallInviteAcceptStreamAndHangup(t *testing.T) {
	server, _, notifier := testServer(t)
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "phone-token")
	register(t, handler, watchID, "watch", "watch", "watch-token")
	httpServer := httptest.NewServer(handler)
	defer httpServer.Close()

	connect := func(id string) *websocket.Conn {
		t.Helper()
		config, err := websocket.NewConfig("ws"+strings.TrimPrefix(httpServer.URL, "http")+"/v1/live", httpServer.URL)
		if err != nil {
			t.Fatal(err)
		}
		config.Header.Set("X-Installation-ID", id)
		conn, err := websocket.DialConfig(config)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = conn.Close() })
		return conn
	}
	receive := func(conn *websocket.Conn) liveEvent {
		t.Helper()
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		var raw string
		if err := websocket.Message.Receive(conn, &raw); err != nil {
			t.Fatal(err)
		}
		var event liveEvent
		if err := json.Unmarshal([]byte(raw), &event); err != nil {
			t.Fatal(err)
		}
		return event
	}
	send := func(conn *websocket.Conn, event liveEvent) {
		t.Helper()
		raw, err := json.Marshal(event)
		if err != nil {
			t.Fatal(err)
		}
		if err := websocket.Message.Send(conn, string(raw)); err != nil {
			t.Fatal(err)
		}
	}

	callID := "77777777-7777-4777-8777-777777777777"
	phone := connect(phoneID)
	send(phone, liveEvent{Type: "call", CallID: callID, RecipientUsername: "watch"})
	if got := receive(phone); got.Type != "ringing" || got.CallID != callID {
		t.Fatalf("caller received %#v", got)
	}
	_, _, liveCallIDs, liveCallers := notifier.snapshot()
	if len(liveCallIDs) != 1 || liveCallIDs[0] != callID || liveCallers[0] != "phone" {
		t.Fatalf("unexpected invite notification: %#v %#v", liveCallIDs, liveCallers)
	}

	watch := connect(watchID)
	if got := receive(watch); got.Type != "incoming" || got.CallerUsername != "phone" {
		t.Fatalf("recipient received %#v", got)
	}
	send(watch, liveEvent{Type: "accept", CallID: callID})
	if got := receive(phone); got.Type != "connected" || got.PeerUsername != "watch" {
		t.Fatalf("caller connected %#v", got)
	}
	if got := receive(watch); got.Type != "connected" || got.PeerUsername != "phone" {
		t.Fatalf("recipient connected %#v", got)
	}
	_ = watch.Close()
	if got := receive(phone); got.Type != "reconnecting" || got.CallID != callID {
		t.Fatalf("caller reconnecting %#v", got)
	}
	watch = connect(watchID)
	send(watch, liveEvent{Type: "resume", CallID: callID})
	if got := receive(phone); got.Type != "connected" || got.Generation != 2 {
		t.Fatalf("caller resumed %#v", got)
	}
	if got := receive(watch); got.Type != "connected" || got.Generation != 2 {
		t.Fatalf("recipient resumed %#v", got)
	}

	streamID := "88888888-8888-4888-8888-888888888888"
	send(phone, liveEvent{Type: "start", StreamID: streamID, RecipientUsername: "watch", SamplePeriodMs: 10})
	if got := receive(watch); got.Type != "start" || got.StreamID != streamID {
		t.Fatalf("stream start %#v", got)
	}
	zero := 0
	send(phone, liveEvent{Type: "samples", StreamID: streamID, StartIndex: &zero, Amplitudes: []int{0, 64, 255}})
	if got := receive(watch); got.Type != "samples" || got.StartIndex == nil || *got.StartIndex != 0 {
		t.Fatalf("samples %#v", got)
	}

	send(phone, liveEvent{Type: "hangup", CallID: callID})
	if got := receive(phone); got.Type != "ended" || got.Reason != "hangup" {
		t.Fatalf("caller ended %#v", got)
	}
	if got := receive(watch); got.Type != "ended" || got.Reason != "hangup" {
		t.Fatalf("recipient ended %#v", got)
	}
	send(watch, liveEvent{Type: "resume", CallID: callID})
	if got := receive(watch); got.Type != "error" || got.Code != "CALL_UNAVAILABLE" {
		t.Fatalf("stale call response %#v", got)
	}
}

func TestUnansweredCallTimesOut(t *testing.T) {
	server, _, _ := testServer(t)
	server.now = time.Now
	server.hub.ringTimeout = 30 * time.Millisecond
	handler := server.Handler()
	register(t, handler, phoneID, "phone", "phone", "")
	register(t, handler, watchID, "watch", "watch", "watch-token")
	httpServer := httptest.NewServer(handler)
	defer httpServer.Close()

	config, err := websocket.NewConfig("ws"+strings.TrimPrefix(httpServer.URL, "http")+"/v1/live", httpServer.URL)
	if err != nil {
		t.Fatal(err)
	}
	config.Header.Set("X-Installation-ID", phoneID)
	phone, err := websocket.DialConfig(config)
	if err != nil {
		t.Fatal(err)
	}
	defer phone.Close()
	callID := "99999999-9999-4999-8999-999999999999"
	raw, _ := json.Marshal(liveEvent{Type: "call", CallID: callID, RecipientUsername: "watch"})
	if err := websocket.Message.Send(phone, string(raw)); err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{"ringing", "ended"} {
		_ = phone.SetDeadline(time.Now().Add(time.Second))
		var message string
		if err := websocket.Message.Receive(phone, &message); err != nil {
			t.Fatal(err)
		}
		var event liveEvent
		if err := json.Unmarshal([]byte(message), &event); err != nil {
			t.Fatal(err)
		}
		if event.Type != expected {
			t.Fatalf("expected %s, got %#v", expected, event)
		}
		if expected == "ended" && event.Reason != "unanswered" {
			t.Fatalf("unexpected timeout reason %#v", event)
		}
	}
}
