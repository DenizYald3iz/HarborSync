package com.harborsync.taskassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harborsync.taskassignment.client.VesselServiceClient;
import com.harborsync.taskassignment.dto.CongestionAlertEvent;
import com.harborsync.taskassignment.dto.TaskCreatedEvent;
import com.harborsync.taskassignment.dto.TaskFailedEvent;
import com.harborsync.taskassignment.dto.VesselResponse;
import com.harborsync.taskassignment.messaging.producer.TaskCreatedProducer;
import com.harborsync.taskassignment.messaging.producer.TaskFailedProducer;
import com.harborsync.taskassignment.model.Task;
import com.harborsync.taskassignment.model.TaskStatus;
import com.harborsync.taskassignment.repository.TaskRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskAssignmentServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private VesselServiceClient vesselServiceClient;

    @Mock
    private TaskCreatedProducer taskCreatedProducer;

    @Mock
    private TaskFailedProducer taskFailedProducer;

    @InjectMocks
    private TaskAssignmentService taskAssignmentService;

    private CongestionAlertEvent alert;

    @BeforeEach
    void setUp() {
        alert = new CongestionAlertEvent(
                "OP-2025-001",
                "SECTOR_CRITICAL",
                "B-12",
                "HIGH",
                0.94,
                "REDIRECT_CRANE",
                "2025-01-15T14:28:03Z"
        );
    }

    @Test
    void handleAlertShouldSavePendingTaskAndPublishTaskCreatedEvent() {
        UUID vesselId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(vesselServiceClient.getArrivingVessels()).thenReturn(List.of(
                new VesselResponse(vesselId, "MV-Ankara", "ARRIVING", "B12", LocalDateTime.now())
        ));
        when(vesselServiceClient.reserveBerth(eq(vesselId), eq("B12")))
                .thenReturn(new VesselResponse(vesselId, "MV-Ankara", "BERTHED", "B12", LocalDateTime.now()));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(taskId);
            return task;
        });

        Task result = taskAssignmentService.handleAlert(alert);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(result.getAssignedUnit()).isEqualTo("Crane-B12");
        assertThat(result.getPriority()).isEqualTo("HIGH");

        verify(vesselServiceClient).reserveBerth(vesselId, "B12");

        ArgumentCaptor<TaskCreatedEvent> eventCaptor = ArgumentCaptor.forClass(TaskCreatedEvent.class);
        verify(taskCreatedProducer).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().taskId()).isEqualTo(taskId);
        assertThat(eventCaptor.getValue().sector()).isEqualTo("B-12");
        assertThat(eventCaptor.getValue().assignedUnit()).isEqualTo("Crane-B12");

        verify(taskFailedProducer, never()).publish(any());
    }

    @Test
    void handleAlertShouldStillCreateTaskWhenVesselListIsEmpty() {
        when(vesselServiceClient.getArrivingVessels()).thenReturn(List.of());
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Task result = taskAssignmentService.handleAlert(alert);

        assertThat(result.getAssignedUnit()).isEqualTo("Crane-1");
        assertThat(result.getStatus()).isEqualTo(TaskStatus.PENDING);
        verify(taskCreatedProducer).publish(any(TaskCreatedEvent.class));
        verify(taskFailedProducer, never()).publish(any());
    }

    @Test
    void handleAlertShouldMarkTaskFailedAndPublishTaskFailedWhenPublishFails() {
        when(vesselServiceClient.getArrivingVessels()).thenReturn(List.of());
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("rabbitmq unavailable"))
                .when(taskCreatedProducer)
                .publish(any(TaskCreatedEvent.class));

        Task result = taskAssignmentService.handleAlert(alert);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        verify(taskRepository, times(1)).save(any(Task.class));
        verify(taskFailedProducer).publish(any(TaskFailedEvent.class));
    }

    @Test
    void handleAlertShouldCompensateBerthReservationWhenPublishFails() {
        UUID vesselId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(vesselServiceClient.getArrivingVessels()).thenReturn(List.of(
                new VesselResponse(vesselId, "MV-Ankara", "ARRIVING", "B12", LocalDateTime.now())
        ));
        when(vesselServiceClient.reserveBerth(eq(vesselId), eq("B12")))
                .thenReturn(new VesselResponse(vesselId, "MV-Ankara", "BERTHED", "B12", LocalDateTime.now()));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(taskId);
            return task;
        });
        doThrow(new RuntimeException("rabbitmq unavailable"))
                .when(taskCreatedProducer)
                .publish(any(TaskCreatedEvent.class));

        Task result = taskAssignmentService.handleAlert(alert);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        verify(vesselServiceClient).reserveBerth(vesselId, "B12");
        verify(vesselServiceClient).releaseBerth(vesselId);
        verify(taskFailedProducer).publish(any(TaskFailedEvent.class));
    }
}
