package main

import (
	"context"
	"fmt"
	"time"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

type Notifier interface {
	NotifyTouch(ctx context.Context, token, touchID string) error
	NotifyLiveInvite(ctx context.Context, token, callID, callerUsername string) error
}

type logNotifier struct{}

func (logNotifier) NotifyTouch(_ context.Context, token, touchID string) error {
	if token != "" {
		logAt(warnLevel, "fcm disabled notification=touch touch_id=%s", touchID)
	}
	return nil
}

func (logNotifier) NotifyLiveInvite(_ context.Context, token, callID, callerUsername string) error {
	if token != "" {
		logAt(warnLevel, "fcm disabled notification=live_invite call_id=%s caller=%s", callID, callerUsername)
	}
	return nil
}

type fcmNotifier struct {
	client *messaging.Client
}

func NewNotifier(ctx context.Context, projectID, credentialsFile string) (Notifier, error) {
	if projectID == "" || credentialsFile == "" {
		logAt(warnLevel, "fcm disabled project_id_set=%t credentials_file_set=%t", projectID != "", credentialsFile != "")
		return logNotifier{}, nil
	}
	app, err := firebase.NewApp(ctx, &firebase.Config{ProjectID: projectID}, option.WithCredentialsFile(credentialsFile))
	if err != nil {
		return nil, fmt.Errorf("initialize firebase: %w", err)
	}
	client, err := app.Messaging(ctx)
	if err != nil {
		return nil, fmt.Errorf("initialize firebase messaging: %w", err)
	}
	logAt(infoLevel, "fcm enabled project_id=%s credentials_file=%s", projectID, credentialsFile)
	return &fcmNotifier{client: client}, nil
}

func (n *fcmNotifier) NotifyTouch(ctx context.Context, token, touchID string) error {
	if token == "" {
		return nil
	}
	messageID, err := n.client.Send(ctx, &messaging.Message{
		Token: token,
		Data: map[string]string{
			"type":    "touch_available",
			"touchId": touchID,
		},
		Android: &messaging.AndroidConfig{Priority: "high"},
	})
	if err != nil {
		logAt(errorLevel, "fcm send failed notification=touch touch_id=%s error=%v", touchID, err)
	} else {
		logAt(debugLevel, "fcm send succeeded notification=touch touch_id=%s message_id=%s", touchID, messageID)
	}
	return err
}

func (n *fcmNotifier) NotifyLiveInvite(ctx context.Context, token, callID, callerUsername string) error {
	if token == "" {
		return nil
	}
	ttl := 60 * time.Second
	messageID, err := n.client.Send(ctx, &messaging.Message{
		Token: token,
		Data: map[string]string{
			"type":           "live_invite",
			"callId":         callID,
			"callerUsername": callerUsername,
		},
		Android: &messaging.AndroidConfig{Priority: "high", TTL: &ttl},
	})
	if err != nil {
		logAt(errorLevel, "fcm send failed notification=live_invite call_id=%s caller=%s error=%v", callID, callerUsername, err)
	} else {
		logAt(debugLevel, "fcm send succeeded notification=live_invite call_id=%s message_id=%s", callID, messageID)
	}
	return err
}
