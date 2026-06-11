# Event Contracts

Exchange: `harborsync.exchange` (`direct`)

Queues:

- `telemetry.processed`
- `congestion.alert.task-assignment` (bound with routing key `congestion.alert`)
- `congestion.alert.notification` (bound with routing key `congestion.alert`)
- `task.created`
- `task.failed`
- `vessel.arrived`
- `vessel.docked`
- `vessel.departed`
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

Routing key: `congestion.alert`

Consumer queues:

- Task Assignment: `congestion.alert.task-assignment`
- Notification Service: `congestion.alert.notification`

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

## `task.failed`

Producer: Task Assignment (Saga compensation)

Consumer: Notification Service

```json
{
  "correlationId": "OP-2025-001",
  "taskId": "550e8400-e29b-41d4-a716",
  "sector": "B-12",
  "reason": "Task publish failed: rabbitmq unavailable",
  "timestamp": "2025-01-15T14:28:05Z"
}
```

## `vessel.arrived`

Producer: Vessel Service (on vessel registration, status=ARRIVING)

Consumer: Notification Service

```json
{
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "vesselId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "MSC Emma",
  "imoNumber": "IMO9876543",
  "status": "ARRIVING",
  "berth": "A1",
  "eta": "2026-06-11T14:00:00",
  "timestamp": "2026-06-11T13:55:00Z"
}
```

## `vessel.docked`

Producer: Vessel Service (on status update to DOCKED)

Consumer: Notification Service

```json
{
  "correlationId": "OP-2025-001",
  "vesselId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "MSC Emma",
  "imoNumber": "IMO9876543",
  "status": "DOCKED",
  "berth": "A1",
  "eta": "2026-06-11T14:00:00",
  "timestamp": "2026-06-11T14:30:00Z"
}
```

## `vessel.departed`

Producer: Vessel Service (on status update to DEPARTED)

Consumer: Notification Service

```json
{
  "correlationId": "OP-2025-001",
  "vesselId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "MSC Emma",
  "imoNumber": "IMO9876543",
  "status": "DEPARTED",
  "berth": "A1",
  "eta": null,
  "timestamp": "2026-06-11T18:00:00Z"
}
```
