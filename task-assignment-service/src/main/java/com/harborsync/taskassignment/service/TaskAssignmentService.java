package com.harborsync.taskassignment.service;

import com.harborsync.taskassignment.client.VesselServiceClient;
import com.harborsync.taskassignment.dto.CongestionAlertEvent;
import com.harborsync.taskassignment.dto.TaskCreatedEvent;
import com.harborsync.taskassignment.dto.TaskFailedEvent;
import com.harborsync.taskassignment.dto.VesselResponse;
import com.harborsync.taskassignment.exception.TaskNotFoundException;
import com.harborsync.taskassignment.messaging.producer.TaskCreatedProducer;
import com.harborsync.taskassignment.messaging.producer.TaskFailedProducer;
import com.harborsync.taskassignment.model.Task;
import com.harborsync.taskassignment.model.TaskStatus;
import com.harborsync.taskassignment.repository.TaskRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(TaskAssignmentService.class);

    private final TaskRepository taskRepository;
    private final VesselServiceClient vesselServiceClient;
    private final TaskCreatedProducer taskCreatedProducer;
    private final TaskFailedProducer taskFailedProducer;

    public TaskAssignmentService(
            TaskRepository taskRepository,
            VesselServiceClient vesselServiceClient,
            TaskCreatedProducer taskCreatedProducer,
            TaskFailedProducer taskFailedProducer) {
        this.taskRepository = taskRepository;
        this.vesselServiceClient = vesselServiceClient;
        this.taskCreatedProducer = taskCreatedProducer;
        this.taskFailedProducer = taskFailedProducer;
    }

    @Transactional
    public Task handleAlert(CongestionAlertEvent alert) {
        validateAlert(alert);

        List<VesselResponse> arrivingVessels = vesselServiceClient.getArrivingVessels();
        String assignedUnit = determineAssignedUnit(alert, arrivingVessels);

        VesselResponse vesselToReserve = arrivingVessels.stream()
                .filter(v -> StringUtils.hasText(v.berth()))
                .findFirst()
                .orElse(null);

        UUID reservedVesselId = null;
        if (vesselToReserve != null) {
            try {
                vesselServiceClient.reserveBerth(vesselToReserve.id(), vesselToReserve.berth());
                reservedVesselId = vesselToReserve.id();
                log.info("Berth reserved for vesselId={} berth={}",
                        reservedVesselId, vesselToReserve.berth());
            } catch (RuntimeException ex) {
                log.warn("Berth reservation failed vesselId={}, proceeding without reservation: {}",
                        vesselToReserve.id(), ex.getMessage());
            }
        }

        Task task = new Task();
        task.setSector(alert.sector());
        task.setAlertType(alert.alertType());
        task.setAssignedUnit(assignedUnit);
        task.setPriority(normalizePriority(alert.severity()));
        task.setStatus(TaskStatus.PENDING);
        task.setCorrelationId(alert.correlationId());

        Task savedTask = taskRepository.save(task);
        log.info("Task saved id={} sector={} assignedUnit={} correlationId={}",
                savedTask.getId(), savedTask.getSector(), savedTask.getAssignedUnit(), savedTask.getCorrelationId());

        try {
            taskCreatedProducer.publish(new TaskCreatedEvent(
                    alert.correlationId(),
                    savedTask.getId(),
                    savedTask.getSector(),
                    savedTask.getAssignedUnit(),
                    savedTask.getPriority(),
                    Instant.now()
            ));
        } catch (RuntimeException ex) {
            savedTask.setStatus(TaskStatus.FAILED);
            log.error("Task event publish failed. taskId={} correlationId={} markingStatus=FAILED",
                    savedTask.getId(), savedTask.getCorrelationId(), ex);

            if (reservedVesselId != null) {
                try {
                    vesselServiceClient.releaseBerth(reservedVesselId);
                    log.info("Berth released for vesselId={} as part of Saga compensation",
                            reservedVesselId);
                } catch (RuntimeException releaseEx) {
                    log.error("Saga compensation failed to release berth for vesselId={}",
                            reservedVesselId, releaseEx);
                }
            }

            try {
                taskFailedProducer.publish(new TaskFailedEvent(
                        alert.correlationId(),
                        savedTask.getId(),
                        savedTask.getSector(),
                        "Task publish failed: " + ex.getMessage(),
                        Instant.now()
                ));
            } catch (RuntimeException failedEx) {
                log.error("Failed to publish task.failed event for taskId={}",
                        savedTask.getId(), failedEx);
            }

            return savedTask;
        }

        return savedTask;
    }

    @Transactional(readOnly = true)
    public List<Task> getPendingTasks() {
        return taskRepository.findByStatus(TaskStatus.PENDING);
    }

    @Transactional
    public Task completeTask(java.util.UUID id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
        task.setStatus(TaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        return taskRepository.save(task);
    }

    String determineAssignedUnit(CongestionAlertEvent alert, List<VesselResponse> vessels) {
        String berth = vessels.stream()
                .map(VesselResponse::berth)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);

        String alertType = Objects.toString(alert.alertType(), "").toUpperCase(Locale.ROOT);
        if ("IMMEDIATE_ACTION".equals(alertType)) {
            return berth == null ? "Emergency-Team-1" : "Emergency-Team-" + berth;
        }
        if ("SECTOR_CRITICAL".equals(alertType) || "HIGH".equalsIgnoreCase(alert.severity())) {
            return berth == null ? "Crane-1" : "Crane-" + berth;
        }
        return berth == null ? "Team-1" : "Team-" + berth;
    }

    private void validateAlert(CongestionAlertEvent alert) {
        if (alert == null) {
            throw new IllegalArgumentException("Congestion alert must not be null");
        }
        if (!StringUtils.hasText(alert.sector())) {
            throw new IllegalArgumentException("Congestion alert sector must not be blank");
        }
        if (!StringUtils.hasText(alert.alertType())) {
            throw new IllegalArgumentException("Congestion alert type must not be blank");
        }
    }

    private String normalizePriority(String severity) {
        if (!StringUtils.hasText(severity)) {
            return "LOW";
        }
        String normalized = severity.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "HIGH", "MEDIUM", "LOW" -> normalized;
            default -> "LOW";
        };
    }
}

