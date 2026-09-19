package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestAdminPanelIsStaticAndDoesNotExposeData(t *testing.T) {
	server, _, _ := testServer(t)
	handler := server.Handler()

	redirect := httptest.NewRecorder()
	handler.ServeHTTP(redirect, httptest.NewRequest(http.MethodGet, "/admin", nil))
	if redirect.Code != http.StatusFound || redirect.Header().Get("Location") != "/admin/" {
		t.Fatalf("admin redirect: %d %q", redirect.Code, redirect.Header().Get("Location"))
	}
	result := httptest.NewRecorder()
	handler.ServeHTTP(result, httptest.NewRequest(http.MethodGet, "/admin/", nil))
	if result.Code != http.StatusOK || !strings.Contains(result.Body.String(), "Live diagnostics") {
		t.Fatalf("admin panel: %d %s", result.Code, result.Body.String())
	}
	if got := result.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("cache control: %q", got)
	}
	if strings.Contains(result.Body.String(), "TOUCH_ADMIN_TOKEN") {
		t.Fatal("admin panel must not embed server admin token")
	}
	asset := httptest.NewRecorder()
	handler.ServeHTTP(asset, httptest.NewRequest(http.MethodGet, "/admin/app.js", nil))
	if asset.Code != http.StatusOK || !strings.Contains(asset.Body.String(), "admin/clients") {
		t.Fatalf("admin JavaScript asset: %d %s", asset.Code, asset.Body.String())
	}
}
