package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

var (
	errNotFound            = errors.New("not found")
	errHandleConflict      = errors.New("handle already registered")
	errIdempotencyConflict = errors.New("idempotency conflict")
)

type Store struct {
	db *sql.DB
}

type Installation struct {
	ID        string
	Handle    string
	Platform  string
	FCMToken  string
	UpdatedAt int64
}

type TouchMessage struct {
	ID                 string
	ClientMessageID    string
	SenderHandle       string
	RecipientHandle    string
	SamplePeriodMillis int
	Amplitudes         []int
	CreatedAt          int64
	ExpiresAt          int64
	ReceivedAt         *int64
	PlayedAt           *int64
}

func OpenStore(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;`); err != nil {
		db.Close()
		return nil, err
	}
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS installations (
    id TEXT PRIMARY KEY,
    handle TEXT NOT NULL COLLATE NOCASE UNIQUE,
    platform TEXT NOT NULL,
    fcm_token TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS touches (
    id TEXT PRIMARY KEY,
    client_message_id TEXT NOT NULL,
    sender_installation_id TEXT NOT NULL REFERENCES installations(id),
    recipient_installation_id TEXT NOT NULL REFERENCES installations(id),
    sender_handle TEXT NOT NULL,
    recipient_handle TEXT NOT NULL,
    sample_period_ms INTEGER NOT NULL,
    amplitudes BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    received_at INTEGER,
    played_at INTEGER,
    UNIQUE(sender_installation_id, client_message_id)
);
CREATE INDEX IF NOT EXISTS idx_touches_recipient_pending
    ON touches(recipient_installation_id, received_at, created_at);
CREATE INDEX IF NOT EXISTS idx_touches_expires_at ON touches(expires_at);
`)
	return err
}

func (s *Store) Register(ctx context.Context, in Installation, now time.Time) (Installation, error) {
	stamp := now.UnixMilli()
	_, err := s.db.ExecContext(ctx, `
INSERT INTO installations(id, handle, platform, fcm_token, created_at, updated_at)
VALUES(?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET
    handle = excluded.handle,
    platform = excluded.platform,
    fcm_token = excluded.fcm_token,
    updated_at = excluded.updated_at`,
		in.ID, in.Handle, in.Platform, in.FCMToken, stamp, stamp)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			return Installation{}, errHandleConflict
		}
		return Installation{}, err
	}
	in.UpdatedAt = stamp
	return in, nil
}

func (s *Store) Installation(ctx context.Context, id string) (Installation, error) {
	var out Installation
	err := s.db.QueryRowContext(ctx,
		`SELECT id, handle, platform, fcm_token, updated_at FROM installations WHERE id = ?`, id,
	).Scan(&out.ID, &out.Handle, &out.Platform, &out.FCMToken, &out.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Installation{}, errNotFound
	}
	return out, err
}

func (s *Store) InstallationByUsername(ctx context.Context, username string) (Installation, error) {
	var out Installation
	err := s.db.QueryRowContext(ctx, `SELECT id, handle, platform, fcm_token, updated_at FROM installations WHERE handle = ? COLLATE NOCASE`, username).Scan(&out.ID, &out.Handle, &out.Platform, &out.FCMToken, &out.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Installation{}, errNotFound
	}
	return out, err
}

func (s *Store) CreateTouch(ctx context.Context, senderID string, in TouchMessage, now time.Time) (TouchMessage, string, bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return TouchMessage{}, "", false, err
	}
	defer tx.Rollback()

	var senderHandle string
	if err := tx.QueryRowContext(ctx, `SELECT handle FROM installations WHERE id = ?`, senderID).Scan(&senderHandle); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return TouchMessage{}, "", false, errNotFound
		}
		return TouchMessage{}, "", false, err
	}
	var recipientID, recipientHandle, fcmToken string
	if err := tx.QueryRowContext(ctx,
		`SELECT id, handle, fcm_token FROM installations WHERE handle = ? COLLATE NOCASE`, in.RecipientHandle,
	).Scan(&recipientID, &recipientHandle, &fcmToken); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return TouchMessage{}, "", false, fmt.Errorf("recipient: %w", errNotFound)
		}
		return TouchMessage{}, "", false, err
	}

	var existing TouchMessage
	var payload []byte
	err = tx.QueryRowContext(ctx,
		`SELECT id, sender_handle, recipient_handle, sample_period_ms, amplitudes, created_at, expires_at, received_at, played_at
FROM touches WHERE sender_installation_id = ? AND client_message_id = ?`, senderID, in.ClientMessageID,
	).Scan(&existing.ID, &existing.SenderHandle, &existing.RecipientHandle, &existing.SamplePeriodMillis, &payload,
		&existing.CreatedAt, &existing.ExpiresAt, &existing.ReceivedAt, &existing.PlayedAt)
	if err == nil {
		existing.ClientMessageID = in.ClientMessageID
		existing.Amplitudes = make([]int, len(payload))
		for i, value := range payload {
			existing.Amplitudes[i] = int(value)
		}
		if existing.RecipientHandle != recipientHandle || existing.SamplePeriodMillis != in.SamplePeriodMillis || !sameAmplitudes(existing.Amplitudes, in.Amplitudes) {
			return TouchMessage{}, "", false, errIdempotencyConflict
		}
		return existing, fcmToken, false, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return TouchMessage{}, "", false, err
	}

	payload = make([]byte, len(in.Amplitudes))
	for i, amplitude := range in.Amplitudes {
		payload[i] = byte(amplitude)
	}
	in.ID = newID()
	in.SenderHandle = senderHandle
	in.RecipientHandle = recipientHandle
	in.CreatedAt = now.UnixMilli()
	in.ExpiresAt = now.Add(30 * 24 * time.Hour).UnixMilli()
	_, err = tx.ExecContext(ctx, `
INSERT INTO touches(
    id, client_message_id, sender_installation_id, recipient_installation_id,
    sender_handle, recipient_handle, sample_period_ms, amplitudes, created_at, expires_at
) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		in.ID, in.ClientMessageID, senderID, recipientID, senderHandle, recipientHandle,
		in.SamplePeriodMillis, payload, in.CreatedAt, in.ExpiresAt)
	if err != nil {
		return TouchMessage{}, "", false, err
	}
	if err := tx.Commit(); err != nil {
		return TouchMessage{}, "", false, err
	}
	return in, fcmToken, true, nil
}

func (s *Store) PendingTouches(ctx context.Context, recipientID string, now time.Time, limit int) ([]TouchMessage, error) {
	if _, err := s.Installation(ctx, recipientID); err != nil {
		return nil, err
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT id, client_message_id, sender_handle, recipient_handle, sample_period_ms,
       amplitudes, created_at, expires_at, received_at, played_at
FROM touches
WHERE recipient_installation_id = ? AND received_at IS NULL AND expires_at > ?
ORDER BY created_at ASC, id ASC
LIMIT ?`, recipientID, now.UnixMilli(), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []TouchMessage
	for rows.Next() {
		var item TouchMessage
		var payload []byte
		var receivedAt, playedAt sql.NullInt64
		if err := rows.Scan(
			&item.ID, &item.ClientMessageID, &item.SenderHandle, &item.RecipientHandle,
			&item.SamplePeriodMillis, &payload, &item.CreatedAt, &item.ExpiresAt, &receivedAt, &playedAt,
		); err != nil {
			return nil, err
		}
		item.Amplitudes = make([]int, len(payload))
		for i, amplitude := range payload {
			item.Amplitudes[i] = int(amplitude)
		}
		if receivedAt.Valid {
			value := receivedAt.Int64
			item.ReceivedAt = &value
		}
		if playedAt.Valid {
			value := playedAt.Int64
			item.PlayedAt = &value
		}
		result = append(result, item)
	}
	return result, rows.Err()
}

func (s *Store) AckTouch(ctx context.Context, recipientID, touchID, status string, now time.Time) error {
	stamp := now.UnixMilli()
	var result sql.Result
	var err error
	switch status {
	case "persisted":
		result, err = s.db.ExecContext(ctx, `
UPDATE touches SET received_at = COALESCE(received_at, ?)
WHERE id = ? AND recipient_installation_id = ?`, stamp, touchID, recipientID)
	case "played":
		result, err = s.db.ExecContext(ctx, `
UPDATE touches SET received_at = COALESCE(received_at, ?), played_at = COALESCE(played_at, ?)
WHERE id = ? AND recipient_installation_id = ?`, stamp, stamp, touchID, recipientID)
	default:
		return fmt.Errorf("invalid ack status")
	}
	if err != nil {
		return err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return errNotFound
	}
	return nil
}

func sameAmplitudes(left, right []int) bool {
	if len(left) != len(right) {
		return false
	}
	for i := range left {
		if left[i] != right[i] {
			return false
		}
	}
	return true
}

func (s *Store) DeleteExpired(ctx context.Context, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM touches WHERE expires_at <= ?`, now.UnixMilli())
	return err
}

func newID() string {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		panic(err)
	}
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(raw[:])
	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:32]
}
