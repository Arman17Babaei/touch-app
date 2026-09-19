package main

import (
	"embed"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

// The panel is static and same-origin. Data still comes exclusively from the
// existing Bearer-protected admin APIs; a token is never embedded by the server.
//
//go:embed admin/index.html admin/app.js admin/styles.css
var adminAssets embed.FS

func (s *Server) adminPanelRedirect(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, "/admin/", http.StatusFound)
}

func (s *Server) adminPanel(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(path.Clean(r.URL.Path), "/admin")
	if name == "" || name == "." || name == "/" {
		name = "/index.html"
	}
	if name != "/index.html" && name != "/app.js" && name != "/styles.css" {
		http.NotFound(w, r)
		return
	}
	asset, err := fs.ReadFile(adminAssets, "admin"+name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	switch path.Ext(name) {
	case ".html":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
	case ".js":
		w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
	case ".css":
		w.Header().Set("Content-Type", "text/css; charset=utf-8")
	}
	_, _ = w.Write(asset)
}
