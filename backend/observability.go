package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

const diagnosticRetention = 7 * 24 * time.Hour

func tokenFingerprint(token string) string {
	if token == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:6])
}

func (s *Server) installationStatus(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	if id != r.PathValue("id") {
		writeAPIError(w, http.StatusForbidden, errorForbidden, "installation ID does not match request path")
		return
	}
	item, err := s.store.Installation(r.Context(), id)
	if err != nil {
		writeAPIError(w, http.StatusNotFound, errorInstallationNotRegistered, "installation is not registered")
		return
	}
	var last string
	_ = s.store.db.QueryRowContext(r.Context(), `SELECT status FROM push_tests WHERE installation_id=? ORDER BY created_at DESC LIMIT 1`, id).Scan(&last)
	writeJSON(w, http.StatusOK, installationStatusResponse{item.ID, item.Handle, item.Platform, item.UpdatedAt, item.FCMToken != "", tokenFingerprint(item.FCMToken), last})
}

func (s *Server) startPushTest(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	if id != r.PathValue("id") {
		writeAPIError(w, http.StatusForbidden, errorForbidden, "installation ID does not match request path")
		return
	}
	item, err := s.store.Installation(r.Context(), id)
	if err != nil {
		writeAPIError(w, http.StatusUnauthorized, errorInstallationNotRegistered, "installation is not registered")
		return
	}
	testID, now := newID(), s.now().UnixMilli()
	status := "pending"
	if item.FCMToken == "" {
		status = "invalid_token"
	}
	_, _ = s.store.db.ExecContext(r.Context(), `INSERT INTO push_tests(id,installation_id,status,created_at) VALUES(?,?,?,?)`, testID, id, status, now)
	if status == "pending" {
		messageID, sendErr := s.notifier.NotifyPushTest(r.Context(), item.FCMToken, testID)
		if sendErr != nil {
			lower := strings.ToLower(sendErr.Error())
			status = "service_error"
			if strings.Contains(lower, "registration-token-not-registered") || strings.Contains(lower, "invalid registration") || strings.Contains(lower, "unregistered") {
				status = "invalid_token"
			}
			_, _ = s.store.db.ExecContext(r.Context(), `UPDATE push_tests SET status=?,error=?,completed_at=? WHERE id=?`, status, sanitizeText(sendErr.Error()), s.now().UnixMilli(), testID)
		} else {
			_, _ = s.store.db.ExecContext(r.Context(), `UPDATE push_tests SET provider_message_id=? WHERE id=?`, messageID, testID)
		}
	}
	writeJSON(w, http.StatusAccepted, pushTestResponse{TestID: testID, Status: status, CreatedAtMs: now})
}

func (s *Server) pushTestStatus(w http.ResponseWriter, r *http.Request) { s.pushTest(w, r, false) }
func (s *Server) ackPushTest(w http.ResponseWriter, r *http.Request)    { s.pushTest(w, r, true) }
func (s *Server) pushTest(w http.ResponseWriter, r *http.Request, ack bool) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	testID := r.PathValue("id")
	var owner, status string
	var created int64
	var completed sql.NullInt64
	err := s.store.db.QueryRowContext(r.Context(), `SELECT installation_id,status,created_at,completed_at FROM push_tests WHERE id=?`, testID).Scan(&owner, &status, &created, &completed)
	if err != nil || owner != id {
		writeAPIError(w, http.StatusNotFound, errorInvalidRequest, "push test not found")
		return
	}
	if ack && status == "pending" {
		now := s.now().UnixMilli()
		status = "delivered"
		completed = sql.NullInt64{Int64: now, Valid: true}
		_, _ = s.store.db.ExecContext(r.Context(), `UPDATE push_tests SET status='delivered',completed_at=? WHERE id=?`, now, testID)
	}
	if !ack && status == "pending" && s.now().UnixMilli()-created > 30_000 {
		now := s.now().UnixMilli()
		status = "timed_out"
		completed = sql.NullInt64{Int64: now, Valid: true}
		_, _ = s.store.db.ExecContext(r.Context(), `UPDATE push_tests SET status='timed_out',completed_at=? WHERE id=?`, now, testID)
	}
	var done *int64
	if completed.Valid {
		v := completed.Int64
		done = &v
	}
	writeJSON(w, http.StatusOK, pushTestResponse{testID, status, created, done})
}

func (s *Server) listContacts(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	rows, err := s.store.db.QueryContext(r.Context(), `SELECT i.handle,c.saved,c.last_used_at FROM contacts c JOIN installations i ON i.id=c.contact_installation_id WHERE c.owner_installation_id=? ORDER BY c.saved DESC,c.last_used_at DESC,i.handle LIMIT 100`, id)
	if err != nil {
		writeAPIError(w, 500, errorInternal, "contacts failed")
		return
	}
	defer rows.Close()
	out := contactsResponse{Contacts: []contactResponse{}}
	for rows.Next() {
		var v contactResponse
		var saved int
		var used sql.NullInt64
		if rows.Scan(&v.Username, &saved, &used) == nil {
			v.Saved = saved != 0
			if used.Valid {
				x := used.Int64
				v.LastUsedAtMs = &x
			}
			out.Contacts = append(out.Contacts, v)
		}
	}
	writeJSON(w, 200, out)
}
func (s *Server) addContact(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	var in contactRequest
	if !decodeJSON(w, r, &in) {
		return
	}
	target, err := s.store.InstallationByUsername(r.Context(), strings.TrimSpace(in.Username))
	if err != nil {
		writeAPIError(w, 404, errorRecipientNotFound, "contact username is not registered")
		return
	}
	if target.ID == id {
		writeAPIError(w, 400, errorInvalidRequest, "cannot add self")
		return
	}
	var count int
	_ = s.store.db.QueryRowContext(r.Context(), `SELECT count(*) FROM contacts WHERE owner_installation_id=? AND saved=1`, id).Scan(&count)
	if count >= 50 {
		writeAPIError(w, 400, errorInvalidRequest, "saved contact limit reached")
		return
	}
	_, err = s.store.db.ExecContext(r.Context(), `INSERT INTO contacts(owner_installation_id,contact_installation_id,saved,created_at) VALUES(?,?,1,?) ON CONFLICT(owner_installation_id,contact_installation_id) DO UPDATE SET saved=1`, id, target.ID, s.now().UnixMilli())
	if err != nil {
		writeAPIError(w, 500, errorInternal, "add contact failed")
		return
	}
	writeJSON(w, 201, contactResponse{Username: target.Handle, Saved: true})
}
func (s *Server) removeContact(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	target, err := s.store.InstallationByUsername(r.Context(), r.PathValue("username"))
	if err != nil {
		writeAPIError(w, 404, errorRecipientNotFound, "contact not found")
		return
	}
	_, _ = s.store.db.ExecContext(r.Context(), `UPDATE contacts SET saved=0 WHERE owner_installation_id=? AND contact_installation_id=?`, id, target.ID)
	w.WriteHeader(204)
}

func (s *Store) markRecent(ctxID, username string, at int64) {
	var target string
	if s.db.QueryRow(`SELECT id FROM installations WHERE handle=? COLLATE NOCASE`, username).Scan(&target) != nil || target == ctxID {
		return
	}
	_, _ = s.db.Exec(`INSERT INTO contacts(owner_installation_id,contact_installation_id,saved,last_used_at,created_at) VALUES(?,?,0,?,?) ON CONFLICT(owner_installation_id,contact_installation_id) DO UPDATE SET last_used_at=excluded.last_used_at`, ctxID, target, at, at)
	_, _ = s.db.Exec(`DELETE FROM contacts WHERE owner_installation_id=? AND saved=0 AND contact_installation_id NOT IN (SELECT contact_installation_id FROM contacts WHERE owner_installation_id=? AND saved=0 ORDER BY last_used_at DESC LIMIT 50)`, ctxID, ctxID)
}

func (s *Server) acceptDiagnostics(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	var in diagnosticBatchRequest
	if !decodeJSON(w, r, &in) {
		return
	}
	if len(in.Events) < 1 || len(in.Events) > 100 {
		writeAPIError(w, 400, errorInvalidRequest, "events must contain 1-100 items")
		return
	}
	now := s.now().UnixMilli()
	tx, err := s.store.db.BeginTx(r.Context(), nil)
	if err != nil {
		writeAPIError(w, 500, errorInternal, "diagnostics failed")
		return
	}
	defer tx.Rollback()
	for _, e := range in.Events {
		if !uuidPattern.MatchString(e.EventID) || !validDiagnosticWord(e.Severity, 16) || !validDiagnosticWord(e.Category, 32) || !validDiagnosticWord(e.Name, 64) {
			writeAPIError(w, 400, errorInvalidRequest, "invalid diagnostic event")
			return
		}
		attrs, _ := json.Marshal(e.Attributes)
		if len(attrs) > 4096 {
			writeAPIError(w, 400, errorInvalidRequest, "diagnostic attributes too large")
			return
		}
		_, err = tx.ExecContext(r.Context(), `INSERT OR IGNORE INTO diagnostic_events(event_id,installation_id,occurred_at,severity,category,name,call_id,touch_id,client_message_id,message,attributes_json,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`, e.EventID, id, e.OccurredAtMs, e.Severity, e.Category, e.Name, nullText(e.CallID), nullText(e.TouchID), nullText(e.ClientMessageID), sanitizeText(e.Message), string(attrs), now)
		if err != nil {
			writeAPIError(w, 500, errorInternal, "diagnostics failed")
			return
		}
	}
	if tx.Commit() != nil {
		writeAPIError(w, 500, errorInternal, "diagnostics failed")
		return
	}
	_, _ = s.store.db.Exec(`DELETE FROM diagnostic_events WHERE received_at<?`, s.now().Add(-diagnosticRetention).UnixMilli())
	w.WriteHeader(204)
}
func validDiagnosticWord(v string, max int) bool {
	if len(v) < 1 || len(v) > max {
		return false
	}
	for _, r := range v {
		if !(r == '_' || r == '-' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9') {
			return false
		}
	}
	return true
}
func sanitizeText(v string) string {
	v = strings.ReplaceAll(v, "\n", " ")
	v = strings.ReplaceAll(v, "\r", " ")
	if len(v) > 512 {
		v = v[:512]
	}
	return v
}
func nullText(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func (s *Server) notificationEvent(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	var in notificationEventRequest
	if !decodeJSON(w, r, &in) {
		return
	}
	column := map[string]string{"received": "received_at", "notification_presented": "presented_at", "opened": "opened_at", "answered": "action_at", "declined": "action_at", "sync_succeeded": "action_at", "sync_failed": "action_at"}[in.Event]
	if column == "" {
		writeAPIError(w, 400, errorInvalidRequest, "invalid notification event")
		return
	}
	stamp := in.OccurredAtMs
	if stamp <= 0 {
		stamp = s.now().UnixMilli()
	}
	result, err := s.store.db.ExecContext(r.Context(), `UPDATE notification_deliveries SET `+column+`=COALESCE(`+column+`,?) WHERE id=? AND recipient_installation_id=?`, stamp, r.PathValue("id"), id)
	if err != nil {
		writeAPIError(w, 500, errorInternal, "notification update failed")
		return
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		writeAPIError(w, 404, errorInvalidRequest, "notification not found")
		return
	}
	_, _ = s.store.db.ExecContext(r.Context(), `INSERT INTO notification_events(delivery_id,event,occurred_at) VALUES(?,?,?)`, r.PathValue("id"), in.Event, stamp)
	logAt(infoLevel, "notification event delivery_id=%s event=%s installation_id=%s", r.PathValue("id"), in.Event, installationLogID(id))
	w.WriteHeader(204)
}

func (s *Server) touchStatus(w http.ResponseWriter, r *http.Request) {
	id, ok := installationID(w, r)
	if !ok {
		return
	}
	var out messageStatusResponse
	var received, played sql.NullInt64
	err := s.store.db.QueryRowContext(r.Context(), `SELECT t.id,t.created_at,t.received_at,t.played_at,COALESCE(n.status,'not_sent'),n.received_at FROM touches t LEFT JOIN notification_deliveries n ON n.reference_id=t.id AND n.kind='touch' WHERE t.id=? AND t.sender_installation_id=?`, r.PathValue("id"), id).Scan(&out.TouchID, &out.AcceptedAtMs, &received, &played, &out.NotificationStatus, &out.NotificationReceivedAtMs)
	if err != nil {
		writeAPIError(w, 404, errorTouchNotFound, "touch not found")
		return
	}
	if received.Valid {
		v := received.Int64
		out.PersistedAtMs = &v
	}
	if played.Valid {
		v := played.Int64
		out.PlayedAtMs = &v
	}
	writeJSON(w, 200, out)
}

func (s *Server) adminOK(w http.ResponseWriter, r *http.Request) bool {
	secret := os.Getenv("TOUCH_ADMIN_TOKEN")
	if secret == "" {
		http.NotFound(w, r)
		return false
	}
	provided := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if len(provided) != len(secret) || subtle.ConstantTimeCompare([]byte(provided), []byte(secret)) != 1 {
		writeAPIError(w, 401, errorUnauthenticated, "invalid admin token")
		return false
	}
	return true
}
func queryLimit(r *http.Request) int {
	v, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if v < 1 || v > 500 {
		return 100
	}
	return v
}
func (s *Server) adminDiagnostics(w http.ResponseWriter, r *http.Request) {
	if !s.adminOK(w, r) {
		return
	}
	callID, installation, severity, messageID := r.URL.Query().Get("callId"), r.URL.Query().Get("installationId"), r.URL.Query().Get("severity"), r.URL.Query().Get("messageId")
	since, _ := strconv.ParseInt(r.URL.Query().Get("sinceMs"), 10, 64)
	rows, err := s.store.db.QueryContext(r.Context(), `SELECT event_id,installation_id,occurred_at,severity,category,name,COALESCE(call_id,''),COALESCE(touch_id,''),message,attributes_json FROM diagnostic_events WHERE (?='' OR call_id=?) AND (?='' OR installation_id=?) AND (?='' OR severity=?) AND (?='' OR client_message_id=? OR touch_id=?) AND (?=0 OR occurred_at>=?) ORDER BY occurred_at DESC LIMIT ?`, callID, callID, installation, installation, severity, severity, messageID, messageID, messageID, since, since, queryLimit(r))
	if err != nil {
		writeAPIError(w, 500, errorInternal, "query failed")
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var event, id, severity, category, name, call, touch, message, attrs string
		var at int64
		if rows.Scan(&event, &id, &at, &severity, &category, &name, &call, &touch, &message, &attrs) == nil {
			var a any
			_ = json.Unmarshal([]byte(attrs), &a)
			items = append(items, map[string]any{"eventId": event, "installationId": id, "occurredAtMs": at, "severity": severity, "category": category, "name": name, "callId": call, "touchId": touch, "message": message, "attributes": a})
		}
	}
	writeJSON(w, 200, map[string]any{"events": items})
}
func (s *Server) adminCalls(w http.ResponseWriter, r *http.Request) {
	if !s.adminOK(w, r) {
		return
	}
	rows, err := s.store.db.QueryContext(r.Context(), `SELECT id,caller_username,recipient_username,state,generation,created_at,accepted_at,connected_at,ended_at,terminal_reason,haptic_frames,audio_frames,audio_bytes,queue_drops FROM call_sessions ORDER BY created_at DESC LIMIT ?`, queryLimit(r))
	if err != nil {
		writeAPIError(w, 500, errorInternal, "query failed")
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id, caller, recipient, state, reason string
		var gen int
		var created int64
		var accepted, connected, ended sql.NullInt64
		var hf, af, ab, drops int64
		if rows.Scan(&id, &caller, &recipient, &state, &gen, &created, &accepted, &connected, &ended, &reason, &hf, &af, &ab, &drops) == nil {
			items = append(items, map[string]any{"callId": id, "callerUsername": caller, "recipientUsername": recipient, "state": state, "generation": gen, "createdAtMs": created, "acceptedAtMs": nullableInt(accepted), "connectedAtMs": nullableInt(connected), "endedAtMs": nullableInt(ended), "reason": reason, "hapticFrames": hf, "audioFrames": af, "audioBytes": ab, "queueDrops": drops})
		}
	}
	writeJSON(w, 200, map[string]any{"calls": items})
}
func (s *Server) adminCall(w http.ResponseWriter, r *http.Request) {
	if !s.adminOK(w, r) {
		return
	}
	id := r.PathValue("id")
	rows, err := s.store.db.QueryContext(r.Context(), `SELECT installation_id,event,details_json,occurred_at FROM call_events WHERE call_id=? ORDER BY occurred_at,id`, id)
	if err != nil {
		writeAPIError(w, 500, errorInternal, "query failed")
		return
	}
	defer rows.Close()
	events := []map[string]any{}
	for rows.Next() {
		var installation sql.NullString
		var event, details string
		var at int64
		if rows.Scan(&installation, &event, &details, &at) == nil {
			var value any
			_ = json.Unmarshal([]byte(details), &value)
			events = append(events, map[string]any{"installationId": installation.String, "event": event, "details": value, "occurredAtMs": at})
		}
	}
	if len(events) == 0 {
		writeAPIError(w, 404, errorInvalidRequest, "call not found")
		return
	}
	writeJSON(w, 200, map[string]any{"callId": id, "events": events})
}
func (s *Server) adminNotifications(w http.ResponseWriter, r *http.Request) {
	if !s.adminOK(w, r) {
		return
	}
	rows, err := s.store.db.QueryContext(r.Context(), `SELECT id,kind,reference_id,status,provider_message_id,error,created_at,received_at,presented_at,opened_at,action_at FROM notification_deliveries ORDER BY created_at DESC LIMIT ?`, queryLimit(r))
	if err != nil {
		writeAPIError(w, 500, errorInternal, "query failed")
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id, kind, ref, status, provider, e string
		var created int64
		var received, presented, opened, action sql.NullInt64
		if rows.Scan(&id, &kind, &ref, &status, &provider, &e, &created, &received, &presented, &opened, &action) == nil {
			items = append(items, map[string]any{"deliveryId": id, "kind": kind, "referenceId": ref, "status": status, "providerMessageId": provider, "error": e, "createdAtMs": created, "receivedAtMs": nullableInt(received), "presentedAtMs": nullableInt(presented), "openedAtMs": nullableInt(opened), "actionAtMs": nullableInt(action)})
		}
	}
	writeJSON(w, 200, map[string]any{"notifications": items})
}
func (s *Server) adminNotification(w http.ResponseWriter, r *http.Request) {
	if !s.adminOK(w, r) {
		return
	}
	id := r.PathValue("id")
	rows, err := s.store.db.QueryContext(r.Context(), `SELECT event,occurred_at FROM notification_events WHERE delivery_id=? ORDER BY occurred_at,id`, id)
	if err != nil {
		writeAPIError(w, 500, errorInternal, "query failed")
		return
	}
	defer rows.Close()
	events := []map[string]any{}
	for rows.Next() {
		var event string
		var at int64
		if rows.Scan(&event, &at) == nil {
			events = append(events, map[string]any{"event": event, "occurredAtMs": at})
		}
	}
	if len(events) == 0 {
		writeAPIError(w, 404, errorInvalidRequest, "notification not found")
		return
	}
	writeJSON(w, 200, map[string]any{"deliveryId": id, "events": events})
}
func nullableInt(v sql.NullInt64) any {
	if !v.Valid {
		return nil
	}
	return v.Int64
}

func (s *Store) createNotification(kind, reference, sender, recipient string, at int64) string {
	cutoff := at - diagnosticRetention.Milliseconds()
	_, _ = s.db.Exec(`DELETE FROM notification_events WHERE occurred_at<?`, cutoff)
	_, _ = s.db.Exec(`DELETE FROM notification_deliveries WHERE created_at<?`, cutoff)
	_, _ = s.db.Exec(`DELETE FROM push_tests WHERE created_at<?`, cutoff)
	_, _ = s.db.Exec(`DELETE FROM call_events WHERE occurred_at<?`, cutoff)
	id := newID()
	_, _ = s.db.Exec(`INSERT INTO notification_deliveries(id,kind,reference_id,sender_installation_id,recipient_installation_id,status,created_at) VALUES(?,?,?,?,?,'pending',?)`, id, kind, reference, nullText(sender), recipient, at)
	return id
}
func (s *Store) finishNotification(id, messageID string, err error) {
	status := "accepted"
	msg := ""
	if err != nil {
		status = "failed"
		msg = sanitizeText(err.Error())
	}
	_, _ = s.db.Exec(`UPDATE notification_deliveries SET status=?,provider_message_id=?,error=? WHERE id=?`, status, messageID, msg, id)
}
func (s *Store) recordCallEvent(callID, installationID, event string, details any, at int64) {
	raw, _ := json.Marshal(details)
	_, _ = s.db.Exec(`INSERT INTO call_events(call_id,installation_id,event,details_json,occurred_at) VALUES(?,?,?,?,?)`, callID, nullText(installationID), event, string(raw), at)
	level := infoLevel
	if event == "client_health" {
		level = debugLevel
	}
	logAt(level, "live call event call_id=%s event=%s installation_id=%s", callID, event, installationLogID(installationID))
}
