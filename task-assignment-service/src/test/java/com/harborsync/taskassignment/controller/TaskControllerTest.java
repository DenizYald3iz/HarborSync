package com.harborsync.taskassignment.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harborsync.taskassignment.exception.GlobalExceptionHandler;
import com.harborsync.taskassignment.exception.TaskNotFoundException;
import com.harborsync.taskassignment.model.Task;
import com.harborsync.taskassignment.model.TaskStatus;
import com.harborsync.taskassignment.service.TaskAssignmentService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskController.class)
@Import(GlobalExceptionHandler.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskAssignmentService taskAssignmentService;

    @Test
    void getPendingTasksReturnsPendingTaskList() throws Exception {
        Task task = new Task();
        task.setId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        task.setSector("B-12");
        task.setAssignedUnit("Crane-1");
        task.setPriority("HIGH");
        task.setStatus(TaskStatus.PENDING);
        when(taskAssignmentService.getPendingTasks()).thenReturn(List.of(task));

        mockMvc.perform(get("/tasks/pending"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sector").value("B-12"))
            .andExpect(jsonPath("$[0].assignedUnit").value("Crane-1"))
            .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void completeTaskMarksTaskCompleted() throws Exception {
        UUID taskId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Task task = new Task();
        task.setId(taskId);
        task.setSector("B-12");
        task.setAssignedUnit("Crane-1");
        task.setPriority("HIGH");
        task.setStatus(TaskStatus.COMPLETED);
        when(taskAssignmentService.completeTask(taskId)).thenReturn(task);

        mockMvc.perform(put("/tasks/{id}/complete", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(taskAssignmentService).completeTask(taskId);
    }

    @Test
    void completeTaskReturnsNotFoundForMissingTask() throws Exception {
        UUID taskId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(taskAssignmentService.completeTask(taskId)).thenThrow(new TaskNotFoundException(taskId));

        mockMvc.perform(put("/tasks/{id}/complete", taskId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }
}
