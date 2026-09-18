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
