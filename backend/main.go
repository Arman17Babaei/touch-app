package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	listenAddr := env("TOUCH_LISTEN_ADDR", ":8080")
	databasePath := env("TOUCH_SQLITE_PATH", "touch.db")
	store, err := OpenStore(databasePath)
	if err != nil {
		log.Fatal(err)
	}
	defer store.Close()

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	notifier, err := NewNotifier(ctx, os.Getenv("TOUCH_FIREBASE_PROJECT_ID"), os.Getenv("GOOGLE_APPLICATION_CREDENTIALS"))
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{
		Addr:              listenAddr,
		Handler:           NewServer(store, notifier).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		log.Printf("touch backend listening on %s", listenAddr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	cleanup := time.NewTicker(time.Hour)
	defer cleanup.Stop()
	for {
		select {
		case <-cleanup.C:
			if err := store.DeleteExpired(context.Background(), time.Now()); err != nil {
				log.Printf("delete expired touches: %v", err)
			}
		case <-ctx.Done():
			shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer shutdownCancel()
			_ = server.Shutdown(shutdownCtx)
			return
		}
	}
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
