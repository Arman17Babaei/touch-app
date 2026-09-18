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
