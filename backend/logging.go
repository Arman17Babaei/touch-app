package main

import (
	"log"
	"os"
	"strings"
)

type logLevel int

const (
	debugLevel logLevel = iota
	infoLevel
	warnLevel
	errorLevel
)

func configuredLogLevel() logLevel {
	switch strings.ToLower(os.Getenv("TOUCH_LOG_LEVEL")) {
	case "debug":
		return debugLevel
	case "warn", "warning":
		return warnLevel
	case "error":
		return errorLevel
	default:
		return infoLevel
	}
}

func logAt(level logLevel, format string, args ...any) {
	if level < configuredLogLevel() {
		return
	}
	prefix := [...]string{"DEBUG", "INFO", "WARN", "ERROR"}[level]
	log.Printf(prefix+" "+format, args...)
}

func installationLogID(id string) string {
	if len(id) < 8 {
		return "missing"
	}
	return id[:8]
}
