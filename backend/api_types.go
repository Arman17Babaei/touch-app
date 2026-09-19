package main

type installationRequest struct {
	Username string `json:"username"`
	Platform string `json:"platform"`
	FCMToken string `json:"fcmToken"`
}

type installationResponse struct {
	InstallationID string `json:"installationId"`
	Username       string `json:"username"`
	Platform       string `json:"platform"`
	UpdatedAtMs    int64  `json:"updatedAtMs"`
}

type installationStatusResponse struct {
	InstallationID      string `json:"installationId"`
	Username            string `json:"username"`
	Platform            string `json:"platform"`
	UpdatedAtMs         int64  `json:"updatedAtMs"`
	FCMTokenPresent     bool   `json:"fcmTokenPresent"`
	FCMTokenFingerprint string `json:"fcmTokenFingerprint,omitempty"`
	LastPushTestStatus  string `json:"lastPushTestStatus,omitempty"`
}

type contactRequest struct {
	Username string `json:"username"`
}
type contactResponse struct {
	Username     string `json:"username"`
	Saved        bool   `json:"saved"`
	LastUsedAtMs *int64 `json:"lastUsedAtMs,omitempty"`
}
type contactsResponse struct {
	Contacts []contactResponse `json:"contacts"`
}

type diagnosticEventRequest struct {
	EventID         string         `json:"eventId"`
	OccurredAtMs    int64          `json:"occurredAtMs"`
	Severity        string         `json:"severity"`
	Category        string         `json:"category"`
	Name            string         `json:"name"`
	CallID          string         `json:"callId,omitempty"`
	TouchID         string         `json:"touchId,omitempty"`
	ClientMessageID string         `json:"clientMessageId,omitempty"`
	Message         string         `json:"message,omitempty"`
	Attributes      map[string]any `json:"attributes,omitempty"`
}
type diagnosticBatchRequest struct {
	Events []diagnosticEventRequest `json:"events"`
}
type notificationEventRequest struct {
	Event        string `json:"event"`
	OccurredAtMs int64  `json:"occurredAtMs"`
}

type pushTestResponse struct {
	TestID        string `json:"testId"`
	Status        string `json:"status"`
	CreatedAtMs   int64  `json:"createdAtMs"`
	CompletedAtMs *int64 `json:"completedAtMs,omitempty"`
}
type messageStatusResponse struct {
	TouchID                  string `json:"touchId"`
	AcceptedAtMs             int64  `json:"acceptedAtMs"`
	NotificationStatus       string `json:"notificationStatus"`
	NotificationReceivedAtMs *int64 `json:"notificationReceivedAtMs,omitempty"`
	PersistedAtMs            *int64 `json:"persistedAtMs,omitempty"`
	PlayedAtMs               *int64 `json:"playedAtMs,omitempty"`
}

type touchSendRequest struct {
	ClientMessageID   string        `json:"clientMessageId"`
	RecipientUsername string        `json:"recipientUsername"`
	SamplePeriodMs    int           `json:"samplePeriodMs"`
	Amplitudes        []int         `json:"amplitudes"`
	Audio             *audioPayload `json:"audio,omitempty"`
}

// audioPayload is intentionally optional: a touch without audio remains a valid v1 message.
// []byte uses JSON base64 encoding, keeping the HTTP API compact and unambiguous.
type audioPayload struct {
	Codec        string `json:"codec"`
	SampleRateHz int    `json:"sampleRateHz"`
	ChannelCount int    `json:"channelCount"`
	DurationMs   int    `json:"durationMs"`
	Data         []byte `json:"data"`
}

type touchAcceptedResponse struct {
	TouchID         string `json:"touchId"`
	ClientMessageID string `json:"clientMessageId"`
	AcceptedAtMs    int64  `json:"acceptedAtMs"`
	Duplicate       bool   `json:"duplicate"`
}

type pendingTouchResponse struct {
	TouchID         string        `json:"touchId"`
	ClientMessageID string        `json:"clientMessageId"`
	SenderUsername  string        `json:"senderUsername"`
	SamplePeriodMs  int           `json:"samplePeriodMs"`
	Amplitudes      []int         `json:"amplitudes"`
	CreatedAtMs     int64         `json:"createdAtMs"`
	ExpiresAtMs     int64         `json:"expiresAtMs"`
	Audio           *audioPayload `json:"audio,omitempty"`
}

type pendingTouchesResponse struct {
	Touches []pendingTouchResponse `json:"touches"`
}

type ackRequest struct {
	State string `json:"state"`
}

type errorResponse struct {
	Error apiError `json:"error"`
}

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

const (
	errorInvalidRequest            = "INVALID_REQUEST"
	errorUnauthenticated           = "UNAUTHENTICATED"
	errorForbidden                 = "FORBIDDEN"
	errorUsernameTaken             = "USERNAME_TAKEN"
	errorInstallationNotRegistered = "INSTALLATION_NOT_REGISTERED"
	errorRecipientNotFound         = "RECIPIENT_NOT_FOUND"
	errorTouchNotFound             = "TOUCH_NOT_FOUND"
	errorIdempotencyConflict       = "IDEMPOTENCY_CONFLICT"
	errorInternal                  = "INTERNAL"
)

func toInstallationResponse(item Installation) installationResponse {
	return installationResponse{
		InstallationID: item.ID,
		Username:       item.Handle,
		Platform:       item.Platform,
		UpdatedAtMs:    item.UpdatedAt,
	}
}

func toPendingTouchResponse(item TouchMessage) pendingTouchResponse {
	return pendingTouchResponse{
		TouchID:         item.ID,
		ClientMessageID: item.ClientMessageID,
		SenderUsername:  item.SenderHandle,
		SamplePeriodMs:  item.SamplePeriodMillis,
		Amplitudes:      item.Amplitudes,
		CreatedAtMs:     item.CreatedAt,
		ExpiresAtMs:     item.ExpiresAt,
		Audio:           item.Audio,
	}
}
