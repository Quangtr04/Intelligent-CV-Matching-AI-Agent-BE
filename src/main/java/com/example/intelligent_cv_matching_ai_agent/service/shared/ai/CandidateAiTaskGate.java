package com.example.intelligent_cv_matching_ai_agent.service.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentGenerationRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.CandidateAiTaskRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CandidateAiTaskGate {

    private final AssessmentGenerationRepository assessments;

    private final CandidateAiTaskRepository tasks;

    private final CurrentUser current;

    private final TransactionTemplate transactions;

    private final ThreadLocal<RunningTask> admitted = new ThreadLocal<>();

    private final Map<UUID, RunningTask> running = new ConcurrentHashMap<>();

    private static final class RunningTask {

        private final UUID owner;

        private final UUID id;

        private final Thread thread = Thread.currentThread();

        private boolean interrupted;

        private RunningTask(
            UUID owner,
            UUID id) {
            this.owner = owner;
            this.id = id;
        }
    }

    public CandidateAiTaskGate(
        AssessmentGenerationRepository assessments,
        CandidateAiTaskRepository tasks,
        CurrentUser current,
        PlatformTransactionManager manager) {
        this.assessments = assessments;
        this.tasks = tasks;
        this.current = current;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Caller holds the same transaction lock when admitting an assessment. */
    public void requireNoOtherTask(
        UUID owner) {
        tasks.expire(owner);
        if (tasks.active(owner)) {
            throw ApiException.conflict("An AI task is already running. Wait for it to finish.");
        }
    }

    private void requireIdle(
        UUID owner) {
        assessments.lockQueue();
        assessments.expireInterrupted();
        requireNoOtherTask(owner);
        if (assessments.activeCount(owner) > 0) {
            throw ApiException.conflict("An AI task is already running. Wait for it to finish.");
        }
    }

    public Map<String, Object> enqueueRoadmap(
        Object request) {
        current.requireRole("CANDIDATE");
        UUID owner = current.id();
        return transactions.execute(status -> {
            requireIdle(owner);
            return tasks.enqueueRoadmap(owner, request);
        });
    }

    public List<Map<String, Object>> recent() {
        current.requireRole("CANDIDATE");
        UUID owner = current.id();
        return transactions.execute(status -> {
            tasks.expire(owner);
            return tasks.recent(owner);
        });
    }

    public void cancel(
        UUID id) {
        current.requireRole("CANDIDATE");
        UUID owner = current.id();
        boolean cancelled = Boolean.TRUE
            .equals(transactions.execute(status -> tasks.cancel(owner, id) == 1 || assessments.cancel(owner, id) == 1));
        if (!cancelled) {
            throw ApiException.conflict("No matching AI task is still running.");
        }
        running.computeIfPresent(
            id,
            (
                key,
                task) -> {
                task.interrupted = true;
                task.thread.interrupt();
                return task;
            }
        );
    }

    public <T> T runReserved(
        UUID owner,
        UUID id,
        Supplier<T> work) {
        RunningTask previous = admitted.get();
        var task = new RunningTask(owner, id);
        running.put(id, task);
        admitted.set(task);
        try {
            requireRunning(task);
            return work.get();
        } finally {
            running.remove(id, task);
            if (task.interrupted) {
                Thread.interrupted();
            }
            if (previous == null) {
                admitted.remove();
            } else {
                admitted.set(previous);
            }
        }
    }

    private boolean reenter(
        UUID owner) {
        RunningTask task = admitted.get();
        if (task == null || !owner.equals(task.owner)) {
            return false;
        }
        requireRunning(task);
        return true;
    }

    private void requireRunning(
        RunningTask task) {
        if (Thread.currentThread().isInterrupted() || !tasks.running(task.id)) {
            throw ApiException.conflict("AI task was cancelled or interrupted.");
        }
    }

    public <T> T run(
        String feature,
        Supplier<T> work) {
        if (!current.hasRole("CANDIDATE")) {
            return work.get();
        }
        return runForCandidate(current.id(), feature, work);
    }

    public <T> T runForActor(
        UUID owner,
        String feature,
        Supplier<T> work) {
        if (reenter(owner) || !assessments.activeCandidate(owner)) {
            return work.get();
        }
        return runForCandidate(owner, feature, work);
    }

    private <T> T runForCandidate(
        UUID owner,
        String feature,
        Supplier<T> work) {
        if (reenter(owner)) {
            return work.get();
        }
        UUID id = transactions.execute(status -> {
            requireIdle(owner);
            return tasks.startCall(owner, feature);
        });
        try {
            T result = runReserved(owner, id, work);
            if (transactions.execute(status -> tasks.finish(id, "SUCCEEDED", null, null)) != 1) {
                throw ApiException.conflict("AI task was cancelled or interrupted.");
            }
            return result;
        } catch (RuntimeException failure) {
            String message =
                failure instanceof ApiException ? failure.getMessage() : "AI task failed. Please try again.";
            transactions.executeWithoutResult(status -> tasks.finish(id, "FAILED", null, message));
            throw failure;
        }
    }
}
