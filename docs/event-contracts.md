# Event Contracts

Exchange: `harborsync.exchange` (`direct`)

Queues:

- `telemetry.processed`
- `congestion.alert`
- `task.created`
- `dlq.errors`

Every business queue should be configured with a dead-letter route to `dlq.errors`.

## `telemetry.processed`

Producer: Telemetry Service

Consumer: Congestion Analysis

```json
{
  "correlationId": "OP-2025-001",
  "sector": "B-12",
  "fillRate": 0.94,
  "blockageDetected": true,
  "droneId": "HD-07",
  "vesselEta": "14:30",
  "timestamp": "2025-01-15T14:28:00Z"
}
```

## `congestion.alert`

Producer: Congestion Analysis

Consumers: Task Assignment, Notification Service

```json
{
  "correlationId": "OP-2025-001",
  "alertType": "SECTOR_CRITICAL",
  "sector": "B-12",
  "severity": "HIGH",
  "fillRate": 0.94,
  "recommendedAction": "REDIRECT_CRANE",
  "timestamp": "2025-01-15T14:28:03Z"
}
```

## `task.created`

Producer: Task Assignment

Consumer: Notification Service

```json
{
  "correlationId": "OP-2025-001",
  "taskId": "550e8400-e29b-41d4-a716",
  "sector": "B-12",
  "assignedUnit": "Crane-3",
  "priority": "HIGH",
  "timestamp": "2025-01-15T14:28:04Z"
}
```
