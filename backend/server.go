package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	uuidPattern   = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$`)
	handlePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.-]{2,31}$`)
)

type Server struct {
	store    *Store
	notifier Notifier
	now      func() time.Time
	hub      *LiveHub
}

func NewServer(store *Store, notifier Notifier) *Server {
	s := &Server{store: store, notifier: notifier, now: time.Now}
	s.hub = NewLiveHub(store, notifier, func() time.Time { return s.now() })
	return s
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("PUT /v1/installations/{id}", s.registerInstallation)
	mux.HandleFunc("GET /v1/installations/{id}", s.installationStatus)
	mux.HandleFunc("POST /v1/installations/{id}/push-tests", s.startPushTest)
	mux.HandleFunc("GET /v1/push-tests/{id}", s.pushTestStatus)
	mux.HandleFunc("POST /v1/push-tests/{id}/ack", s.ackPushTest)
	mux.HandleFunc("GET /v1/contacts", s.listContacts)
	mux.HandleFunc("POST /v1/contacts", s.addContact)
	mux.HandleFunc("DELETE /v1/contacts/{username}", s.removeContact)
	mux.HandleFunc("POST /v1/diagnostics/events", s.acceptDiagnostics)
	mux.HandleFunc("POST /v1/notifications/{id}/events", s.notificationEvent)
	mux.HandleFunc("GET /v1/touches/{id}/status", s.touchStatus)
	mux.HandleFunc("GET /v1/admin/diagnostics", s.adminDiagnostics)
	mux.HandleFunc("GET /v1/admin/calls", s.adminCalls)
	mux.HandleFunc("GET /v1/admin/calls/{id}", s.adminCall)
	mux.HandleFunc("GET /v1/admin/notifications", s.adminNotifications)
	mux.HandleFunc("GET /v1/admin/notifications/{id}", s.adminNotification)
	mux.HandleFunc("POST /v1/touches", s.sendTouch)
	mux.HandleFunc("GET /v1/touches", s.pendingTouches)
	mux.HandleFunc("POST /v1/touches/{id}/ack", s.ackTouch)
	mux.HandleFunc("GET /v1/live", s.live)
	return requestLog(mux)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) registerInstallation(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidPattern.MatchString(id) {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "invalid installation ID")
		return
	}
	authenticatedID, ok := installationID(w, r)
	if !ok {
		return
	}
	if authenticatedID != id {
		writeAPIError(w, http.StatusForbidden, errorUnauthenticated, "installation ID does not match request path")
		return
	}
	var input installationRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	input.Username = strings.TrimSpace(input.Username)
	if !handlePattern.MatchString(input.Username) {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "username must be 3-32 characters using letters, numbers, dot, dash, or underscore")
		return
	}
	if input.Platform != "phone" && input.Platform != "watch" {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "platform must be phone or watch")
		return
	}
	installation, err := s.store.Register(r.Context(), Installation{
		ID: id, Handle: input.Username, Platform: input.Platform, FCMToken: input.FCMToken,
	}, s.now())
	if errors.Is(err, errHandleConflict) {
		writeAPIError(w, http.StatusConflict, errorUsernameTaken, "username is already registered")
		return
	}
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, errorInternal, "registration failed")
		return
	}
	writeJSON(w, http.StatusOK, toInstallationResponse(installation))
}

func (s *Server) sendTouch(w http.ResponseWriter, r *http.Request) {
	senderID, ok := installationID(w, r)
	if !ok {
		return
	}
	var input touchSendRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	input.RecipientUsername = strings.TrimSpace(input.RecipientUsername)
	if !uuidPattern.MatchString(input.ClientMessageID) {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "invalid clientMessageId")
		return
	}
	if !handlePattern.MatchString(input.RecipientUsername) {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "invalid recipientUsername")
		return
	}
	if err := validateTouch(input.SamplePeriodMs, input.Amplitudes); err != nil {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, err.Error())
		return
	}
	if err := validateAudio(input.Audio); err != nil {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, err.Error())
		return
	}
	created, token, isNew, err := s.store.CreateTouch(r.Context(), senderID, TouchMessage{
		ClientMessageID: input.ClientMessageID, RecipientHandle: input.RecipientUsername,
		SamplePeriodMillis: input.SamplePeriodMs, Amplitudes: input.Amplitudes, Audio: input.Audio,
	}, s.now())
	if errors.Is(err, errNotFound) || errors.Is(errors.Unwrap(err), errNotFound) {
		writeAPIError(w, http.StatusNotFound, errorRecipientNotFound, "recipient username is not registered")
		return
	}
	if errors.Is(err, errIdempotencyConflict) {
		writeAPIError(w, http.StatusConflict, errorIdempotencyConflict, "clientMessageId was already used with different content")
		return
	}
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, errorInternal, "send failed")
		return
	}
	if isNew {
		recipient, _ := s.store.InstallationByUsername(r.Context(), created.RecipientHandle)
		deliveryID := s.store.createNotification("touch", created.ID, senderID, recipient.ID, s.now().UnixMilli())
		messageID, notifyErr := s.notifier.NotifyTouch(r.Context(), token, created.ID, deliveryID)
		s.store.finishNotification(deliveryID, messageID, notifyErr)
		if notifyErr != nil {
			logAt(errorLevel, "notify touch failed touch_id=%s error=%v", created.ID, notifyErr)
		}
		s.store.markRecent(senderID, created.RecipientHandle, s.now().UnixMilli())
		s.store.markRecent(recipient.ID, created.SenderHandle, s.now().UnixMilli())
	}
	writeJSON(w, http.StatusAccepted, touchAcceptedResponse{
		TouchID: created.ID, ClientMessageID: created.ClientMessageID,
		AcceptedAtMs: created.CreatedAt, Duplicate: !isNew,
	})
}

func validateAudio(audio *audioPayload) error {
	if audio == nil {
		return nil
	}
	if audio.Codec != "aac-lc" || audio.SampleRateHz != 16000 || audio.ChannelCount != 1 {
		return fmt.Errorf("audio must be mono 16 kHz aac-lc")
	}
	if audio.DurationMs < 1 || audio.DurationMs > 30_000 {
		return fmt.Errorf("audio duration must be 1-30000 ms")
	}
	if len(audio.Data) == 0 || len(audio.Data) > 256*1024 {
		return fmt.Errorf("audio data must be 1-262144 bytes")
	}
	return nil
}

func (s *Server) pendingTouches(w http.ResponseWriter, r *http.Request) {
	recipientID, ok := installationID(w, r)
	if !ok {
		return
	}
	if state := r.URL.Query().Get("state"); state != "" && state != "pending" {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "only state=pending is supported")
		return
	}
	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 100 {
			writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "limit must be 1-100")
			return
		}
		limit = parsed
	}
	items, err := s.store.PendingTouches(r.Context(), recipientID, s.now(), limit)
	if errors.Is(err, errNotFound) {
		writeAPIError(w, http.StatusUnauthorized, errorInstallationNotRegistered, "installation is not registered")
		return
	}
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, errorInternal, "inbox fetch failed")
		return
	}
	response := pendingTouchesResponse{Touches: []pendingTouchResponse{}}
	for _, item := range items {
		response.Touches = append(response.Touches, toPendingTouchResponse(item))
	}
	writeJSON(w, http.StatusOK, response)
}

func (s *Server) ackTouch(w http.ResponseWriter, r *http.Request) {
	recipientID, ok := installationID(w, r)
	if !ok {
		return
	}
	var input ackRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	if input.State != "persisted" && input.State != "played" {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "state must be persisted or played")
		return
	}
	err := s.store.AckTouch(r.Context(), recipientID, r.PathValue("id"), input.State, s.now())
	if errors.Is(err, errNotFound) {
		writeAPIError(w, http.StatusNotFound, errorTouchNotFound, "touch not found")
		return
	}
	if err != nil {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func validateTouch(period int, amplitudes []int) error {
	if period < 1 || period > 100 {
		return fmt.Errorf("samplePeriodMs must be 1-100")
	}
	if len(amplitudes) == 0 || len(amplitudes) > 3000 {
		return fmt.Errorf("amplitudes must contain 1-3000 samples")
	}
	nonSilent := false
	for _, amplitude := range amplitudes {
		if amplitude < 0 || amplitude > 255 {
			return fmt.Errorf("amplitudes must be 0-255")
		}
		if amplitude != 0 {
			nonSilent = true
		}
	}
	if !nonSilent {
		return fmt.Errorf("silent touches cannot be sent")
	}
	return nil
}

func installationID(w http.ResponseWriter, r *http.Request) (string, bool) {
	id := r.Header.Get("X-Installation-ID")
	if !uuidPattern.MatchString(id) {
		logAt(warnLevel, "authentication rejected method=%s path=%s installation_id=%s reason=missing_or_invalid_header", r.Method, r.URL.Path, installationLogID(id))
		writeAPIError(w, http.StatusUnauthorized, errorUnauthenticated, "missing or invalid X-Installation-ID")
		return "", false
	}
	return id, true
}

func decodeJSON(w http.ResponseWriter, r *http.Request, out any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(out); err != nil {
		writeAPIError(w, http.StatusBadRequest, errorInvalidRequest, "invalid JSON")
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeAPIError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorResponse{Error: apiError{Code: code, Message: message}})
}

func requestLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		response := &responseLogWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(response, r)
		level := infoLevel
		if response.status >= 500 {
			level = errorLevel
		} else if response.status >= 400 {
			level = warnLevel
		}
		logAt(level, "http request method=%s path=%s status=%d duration=%s installation_id=%s", r.Method, r.URL.Path, response.status, time.Since(started).Round(time.Millisecond), installationLogID(r.Header.Get("X-Installation-ID")))
	})
}

type responseLogWriter struct {
	http.ResponseWriter
	status int
}

func (w *responseLogWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *responseLogWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	return w.ResponseWriter.(http.Hijacker).Hijack()
}
