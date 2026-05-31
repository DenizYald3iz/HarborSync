package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

type fakeStore struct {
	payload RawTelemetryPayload
	err     error
}

func (s *fakeStore) Store(_ context.Context, payload RawTelemetryPayload) error {
	s.payload = payload
	return s.err
}

type fakePublisher struct {
	event ProcessedTelemetryEvent
	err   error
}

func (p *fakePublisher) Publish(_ context.Context, event ProcessedTelemetryEvent) error {
	p.event = event
	return p.err
}

func (p *fakePublisher) Close() error {
	return nil
}

func TestIngestPublishesProcessedTelemetry(t *testing.T) {
	store := &fakeStore{}
	publisher := &fakePublisher{}
	server := NewServer(store, publisher)
	mux := http.NewServeMux()
	server.RegisterRoutes(mux)

	body := []byte(`{
		"droneId":"HD-07",
		"sector":"B-12",
		"containerCount":94,
		"capacity":100,
		"blockageDetected":true,
		"timestamp":"2025-01-15T14:28:00Z",
		"vesselEta":"14:30"
	}`)
	request := httptest.NewRequest(http.MethodPost, "/telemetry/ingest", bytes.NewReader(body))
	request.Header.Set(correlationHeader, "CID-1")
	response := httptest.NewRecorder()

	mux.ServeHTTP(response, request)

	if response.Code != http.StatusAccepted {
		t.Fatalf("expected status %d, got %d", http.StatusAccepted, response.Code)
	}
	if response.Header().Get(correlationHeader) != "CID-1" {
		t.Fatalf("expected response correlation header")
	}
	if store.payload.DroneID != "HD-07" {
		t.Fatalf("expected Redis state store to receive payload")
	}
	if publisher.event.CorrelationID != "CID-1" {
		t.Fatalf("expected published event correlation id, got %q", publisher.event.CorrelationID)
	}
	if publisher.event.FillRate != 0.94 {
		t.Fatalf("expected fill rate 0.94, got %v", publisher.event.FillRate)
	}
}

func TestIngestRejectsInvalidPayload(t *testing.T) {
	server := NewServer(&fakeStore{}, &fakePublisher{})
	mux := http.NewServeMux()
	server.RegisterRoutes(mux)

	request := httptest.NewRequest(http.MethodPost, "/telemetry/ingest", bytes.NewReader([]byte(`{"droneId":"","capacity":0}`)))
	response := httptest.NewRecorder()

	mux.ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("expected status %d, got %d", http.StatusBadRequest, response.Code)
	}
}

func TestIngestContinuesWhenRedisWriteFails(t *testing.T) {
	store := &fakeStore{err: errors.New("redis unavailable")}
	publisher := &fakePublisher{}
	server := NewServer(store, publisher)
	mux := http.NewServeMux()
	server.RegisterRoutes(mux)

	request := httptest.NewRequest(http.MethodPost, "/telemetry/ingest", bytes.NewReader(validTelemetryJSON()))
	response := httptest.NewRecorder()

	mux.ServeHTTP(response, request)

	if response.Code != http.StatusAccepted {
		t.Fatalf("expected status %d, got %d", http.StatusAccepted, response.Code)
	}
	if publisher.event.DroneID != "HD-07" {
		t.Fatalf("expected event to be published despite Redis error")
	}
}

func TestIngestReturnsBadGatewayWhenPublishFails(t *testing.T) {
	publisher := &fakePublisher{err: errors.New("rabbitmq unavailable")}
	server := NewServer(&fakeStore{}, publisher)
	mux := http.NewServeMux()
	server.RegisterRoutes(mux)

	request := httptest.NewRequest(http.MethodPost, "/telemetry/ingest", bytes.NewReader(validTelemetryJSON()))
	response := httptest.NewRecorder()

	mux.ServeHTTP(response, request)

	if response.Code != http.StatusBadGateway {
		t.Fatalf("expected status %d, got %d", http.StatusBadGateway, response.Code)
	}
}

func TestHealthReportsGoRuntime(t *testing.T) {
	server := NewServer(&fakeStore{}, &fakePublisher{})
	mux := http.NewServeMux()
	server.RegisterRoutes(mux)

	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	response := httptest.NewRecorder()

	mux.ServeHTTP(response, request)

	var payload map[string]string
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("health response is not JSON: %v", err)
	}
	if payload["runtime"] != "go" {
		t.Fatalf("expected go runtime, got %q", payload["runtime"])
	}
}

func validTelemetryJSON() []byte {
	return []byte(`{
		"droneId":"HD-07",
		"sector":"B-12",
		"containerCount":94,
		"capacity":100,
		"blockageDetected":true,
		"timestamp":"2025-01-15T14:28:00Z"
	}`)
}
