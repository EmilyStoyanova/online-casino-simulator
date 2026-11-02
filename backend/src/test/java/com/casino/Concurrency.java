package com.casino;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs a task on several threads that start at the same moment, for tests of concurrent requests. */
public final class Concurrency {

    private Concurrency() {
    }

    /**
     * Runs {@code task} {@code times} times in parallel.
     *
     * @return the result or the exception of each run (the cause of its {@link ExecutionException})
     */
    public static <T> List<Outcome<T>> outcomes(int times, Callable<T> task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(times)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                }
            }
            return outcomes;
        }
    }

    /** Like {@link #outcomes}, but every run must succeed. */
    public static <T> List<T> runTogether(int times, Callable<T> task) throws InterruptedException {
        List<T> results = new ArrayList<>();
        for (Outcome<T> outcome : outcomes(times, task)) {
            if (outcome.failure() != null) {
                throw new AssertionError("A concurrent run failed", outcome.failure());
            }
            results.add(outcome.result());
        }
        return results;
    }

    /** The result of one run, or its failure. */
    public record Outcome<T>(T result, Throwable failure) {
    }
}
