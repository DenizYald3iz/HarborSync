package com.harborsync.taskassignment.repository;

import com.harborsync.taskassignment.model.Task;
import com.harborsync.taskassignment.model.TaskStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByStatus(TaskStatus status);
}

