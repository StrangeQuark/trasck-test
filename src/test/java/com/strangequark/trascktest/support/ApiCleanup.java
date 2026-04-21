package com.strangequark.trascktest.support;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ApiCleanup implements AutoCloseable {
    private final Deque<Runnable> cleanups = new ArrayDeque<>();

    public void add(Runnable cleanup) {
        cleanups.push(cleanup);
    }

    public void delete(AuthSession session, String path) {
        add(() -> {
            int status = session.delete(path).status();
            if (status != 200 && status != 204 && status != 404) {
                throw new AssertionError("Cleanup DELETE " + path + " returned HTTP " + status);
            }
        });
    }

    @Override
    public void close() {
        AssertionError failure = null;
        while (!cleanups.isEmpty()) {
            try {
                cleanups.pop().run();
            } catch (AssertionError ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
