package org.example;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunOnceTest {

    @Test
    void runsTheActionTheFirstTime() {
        AtomicInteger runs = new AtomicInteger();

        new RunOnce(runs::incrementAndGet).run();

        assertEquals(1, runs.get());
    }

    @Test
    void doesNotRunItAgain() {
        AtomicInteger runs = new AtomicInteger();
        RunOnce once = new RunOnce(runs::incrementAndGet);

        once.run();
        once.run();
        once.run();

        assertEquals(1, runs.get());
    }

    @Test
    void aCallerThatArrivesWhileItRunsWaitsUntilItHasFinished() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new ArrayList<>();
        RunOnce once = new RunOnce(() -> {
            events.add("start");
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            events.add("finish");
        });

        Thread first = new Thread(once::run);
        first.start();
        started.await();
        Thread second = new Thread(() -> {
            once.run();
            synchronized (events) {
                events.add("second returned");
            }
        });
        second.start();
        Thread.sleep(150);
        assertTrue(second.isAlive(), "the second caller is held until the action is done");

        release.countDown();
        first.join();
        second.join();

        assertEquals(List.of("start", "finish", "second returned"), events);
    }

    @Test
    void manyThreadsAtOnceRunItExactlyOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        RunOnce once = new RunOnce(() -> {
            runs.incrementAndGet();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            threads.add(new Thread(once::run));
        }

        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, runs.get());
    }

    @Test
    void anActionThatFailsIsNotTriedAgainAndOnlyTheFirstCallerSeesTheFailure() {
        AtomicInteger runs = new AtomicInteger();
        RunOnce once = new RunOnce(() -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, once::run);
        once.run();

        assertEquals(1, runs.get());
    }
}
