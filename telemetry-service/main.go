package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/redis/go-redis/v9"
)

const correlationHeader = "X-Correlation-ID"

type Config struct {
	Port                    string
	RedisAddr               string
	RedisPassword           string
	RedisDB                 int
	DroneStateTTL           time.Duration
	RabbitURL               string
	RabbitExchange          string
	TelemetryProcessedQueue string
	DLQQueue                string
}

type RawTelemetryPayload struct {
	DroneID          string  `json:"droneId"`
	Sector           string  `json:"sector"`
	ContainerCount   int     `json:"containerCount"`
	Capacity         int     `json:"capacity"`
	BlockageDetected bool    `json:"blockageDetected"`
	Timestamp        string  `json:"timestamp"`
	VesselETA        *string `json:"vesselEta"`
}

type ProcessedTelemetryEvent struct {
	CorrelationID    string  `json:"correlationId"`
	Sector           string  `json:"sector"`
	FillRate         float64 `json:"fillRate"`
	BlockageDetected bool    `json:"blockageDetected"`
	DroneID          string  `json:"droneId"`
	VesselETA        *string `json:"vesselEta"`
	Timestamp        string  `json:"timestamp"`
}

type StateStore interface {
	Store(ctx context.Context, payload RawTelemetryPayload) error
}

type EventPublisher interface {
	Publish(ctx context.Context, event ProcessedTelemetryEvent) error
	Close() error
}

type Server struct {
	store     StateStore
	publisher EventPublisher
}

func main() {
	cfg := loadConfig()
	ctx := context.Background()

	redisClient := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
		DB:       cfg.RedisDB,
	})

	publisher, err := connectRabbitMQ(ctx, cfg)
	if err != nil {
		log.Fatalf("rabbitmq connection failed: %v", err)
	}
	defer publisher.Close()

	server := NewServer(&RedisStateStore{client: redisClient, ttl: cfg.DroneStateTTL}, publisher)
	mux := http.NewServeMux()
	server.RegisterRoutes(mux)

	addr := ":" + cfg.Port
	log.Printf("telemetry-service listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}

func loadConfig() Config {
	return Config{
		Port:                    env("PORT", "8082"),
		RedisAddr:               env("REDIS_ADDR", "localhost:6379"),
		RedisPassword:           env("REDIS_PASSWORD", ""),
		RedisDB:                 envInt("REDIS_DB", 0),
		DroneStateTTL:           time.Duration(envInt("DRONE_STATE_TTL_SECONDS", 30)) * time.Second,
		RabbitURL:               env("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/"),
		RabbitExchange:          env("RABBITMQ_EXCHANGE", "harborsync.exchange"),
		TelemetryProcessedQueue: env("TELEMETRY_PROCESSED_QUEUE", "telemetry.processed"),
		DLQQueue:                env("DLQ_QUEUE", "dlq.errors"),
	}
}

func env(key, fallback string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value
}

func envInt(key string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return value
}

func NewServer(store StateStore, publisher EventPublisher) *Server {
	return &Server{store: store, publisher: publisher}
}

func (s *Server) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("POST /telemetry/ingest", s.handleIngest)
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "UP",
		"service": "telemetry-service",
		"runtime": "go",
	})
}

func (s *Server) handleIngest(w http.ResponseWriter, r *http.Request) {
	var payload RawTelemetryPayload
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}

	if err := validateTelemetry(payload); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	correlationID := strings.TrimSpace(r.Header.Get(correlationHeader))
	if correlationID == "" {
		correlationID = newCorrelationID()
	}

	event := ProcessedTelemetryEvent{
		CorrelationID:    correlationID,
		Sector:           payload.Sector,
		FillRate:         float64(payload.ContainerCount) / float64(payload.Capacity),
		BlockageDetected: payload.BlockageDetected,
		DroneID:          payload.DroneID,
		VesselETA:        payload.VesselETA,
		Timestamp:        payload.Timestamp,
	}

	if err := s.store.Store(r.Context(), payload); err != nil {
		log.Printf("[%s] redis state write failed for drone %s: %v", correlationID, payload.DroneID, err)
	}

	if err := s.publisher.Publish(r.Context(), event); err != nil {
		log.Printf("[%s] telemetry.processed publish failed: %v", correlationID, err)
		writeError(w, http.StatusBadGateway, "telemetry publish failed")
		return
	}

	w.Header().Set(correlationHeader, correlationID)
	writeJSON(w, http.StatusAccepted, event)
}

func validateTelemetry(payload RawTelemetryPayload) error {
	if strings.TrimSpace(payload.DroneID) == "" {
		return errors.New("droneId must not be blank")
	}
	if strings.TrimSpace(payload.Sector) == "" {
		return errors.New("sector must not be blank")
	}
	if payload.ContainerCount < 0 {
		return errors.New("containerCount must be >= 0")
	}
	if payload.Capacity <= 0 {
		return errors.New("capacity must be > 0")
	}
	if strings.TrimSpace(payload.Timestamp) == "" {
		return errors.New("timestamp must not be blank")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("response encode failed: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func newCorrelationID() string {
	return fmt.Sprintf("go-%d", time.Now().UnixNano())
}

type RedisStateStore struct {
	client *redis.Client
	ttl    time.Duration
}

func (s *RedisStateStore) Store(ctx context.Context, payload RawTelemetryPayload) error {
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return s.client.Set(ctx, "drone:"+payload.DroneID, raw, s.ttl).Err()
}

type RabbitPublisher struct {
	connection *amqp.Connection
	channel    *amqp.Channel
	exchange   string
	routingKey string
}

func connectRabbitMQ(ctx context.Context, cfg Config) (*RabbitPublisher, error) {
	var lastErr error
	for attempt := 1; attempt <= 30; attempt++ {
		publisher, err := newRabbitPublisher(cfg)
		if err == nil {
			return publisher, nil
		}
		lastErr = err
		log.Printf("rabbitmq connect attempt %d failed: %v", attempt, err)

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
	return nil, lastErr
}

func newRabbitPublisher(cfg Config) (*RabbitPublisher, error) {
	connection, err := amqp.Dial(cfg.RabbitURL)
	if err != nil {
		return nil, err
	}

	channel, err := connection.Channel()
	if err != nil {
		connection.Close()
		return nil, err
	}

	if err := declareRabbitTopology(channel, cfg); err != nil {
		channel.Close()
		connection.Close()
		return nil, err
	}

	return &RabbitPublisher{
		connection: connection,
		channel:    channel,
		exchange:   cfg.RabbitExchange,
		routingKey: cfg.TelemetryProcessedQueue,
	}, nil
}

func declareRabbitTopology(channel *amqp.Channel, cfg Config) error {
	if err := channel.ExchangeDeclare(cfg.RabbitExchange, "direct", true, false, false, false, nil); err != nil {
		return err
	}
	if _, err := channel.QueueDeclare(cfg.DLQQueue, true, false, false, false, nil); err != nil {
		return err
	}

	args := amqp.Table{
		"x-dead-letter-exchange":    "",
		"x-dead-letter-routing-key": cfg.DLQQueue,
	}
	if _, err := channel.QueueDeclare(cfg.TelemetryProcessedQueue, true, false, false, false, args); err != nil {
		return err
	}
	return channel.QueueBind(cfg.TelemetryProcessedQueue, cfg.TelemetryProcessedQueue, cfg.RabbitExchange, false, nil)
}

func (p *RabbitPublisher) Publish(ctx context.Context, event ProcessedTelemetryEvent) error {
	body, err := json.Marshal(event)
	if err != nil {
		return err
	}

	return p.channel.PublishWithContext(ctx, p.exchange, p.routingKey, false, false, amqp.Publishing{
		ContentType:   "application/json",
		DeliveryMode:  amqp.Persistent,
		CorrelationId: event.CorrelationID,
		Timestamp:     time.Now().UTC(),
		Body:          body,
	})
}

func (p *RabbitPublisher) Close() error {
	var err error
	if p.channel != nil {
		err = p.channel.Close()
	}
	if p.connection != nil {
		if closeErr := p.connection.Close(); err == nil {
			err = closeErr
		}
	}
	return err
}
