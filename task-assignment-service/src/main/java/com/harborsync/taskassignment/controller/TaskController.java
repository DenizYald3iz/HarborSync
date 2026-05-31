package com.harborsync.taskassignment.controller;

import com.harborsync.taskassignment.model.Task;
import com.harborsync.taskassignment.service.TaskAssignmentService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskAssignmentService taskAssignmentService;

    public TaskController(TaskAssignmentService taskAssignmentService) {
        this.taskAssignmentService = taskAssignmentService;
    }

    @GetMapping("/pending")
    public List<Task> getPendingTasks() {
        return taskAssignmentService.getPendingTasks();
    }

    @PutMapping("/{id}/complete")
    public Task completeTask(@PathVariable UUID id) {
        return taskAssignmentService.completeTask(id);
    }
}

