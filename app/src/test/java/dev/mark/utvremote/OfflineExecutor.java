package dev.mark.utvremote;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** UI tests control results explicitly and must never contact the real website. */
final class OfflineExecutor extends AbstractExecutorService {
    private boolean closed;
    int requests;
    @Override public void execute(Runnable command) { requests++; }
    @Override public void shutdown() { closed = true; }
    @Override public List<Runnable> shutdownNow() { closed = true; return Collections.emptyList(); }
    @Override public boolean isShutdown() { return closed; }
    @Override public boolean isTerminated() { return closed; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
}
