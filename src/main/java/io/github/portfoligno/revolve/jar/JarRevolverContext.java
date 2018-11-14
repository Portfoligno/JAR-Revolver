package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.github.portfoligno.revolve.jar.ErrorHelper.checkIsInstance;
import static io.github.portfoligno.revolve.jar.ErrorHelper.throwIfFatal;
import static io.github.portfoligno.revolve.jar.PreventorHelper.DO_IN_LEAK_SAFE_CLASS_LOADER;

class JarRevolverContext implements Consumer<Consumer<Runnable>>, BiConsumer<Duration, Runnable> {
  @FunctionalInterface
  interface CleanUp {
    void accept(@NotNull ScheduledExecutorService executorService, @NotNull AtomicInteger remainingCount);
  }

  private final @NotNull ClassLoaderLeakPreventor preventor;
  private final @NotNull AtomicBoolean lock;
  private final @NotNull List<CleanUp> cleanUps;

  JarRevolverContext(
      @NotNull ClassLoaderLeakPreventor preventor, @NotNull AtomicBoolean lock, @NotNull List<CleanUp> cleanUps) {
    this.preventor = preventor;
    this.lock = lock;
    this.cleanUps = cleanUps;
  }

  void scheduleCleanUps(@NotNull ScheduledExecutorService executorService) {
    if (!cleanUps.isEmpty()) {
      AtomicInteger remaining = new AtomicInteger(cleanUps.size());

      // ClassLoaderLeakPreventor#runCleanUps will be called after the last clean-up
      cleanUps.forEach(f -> f.accept(executorService, remaining));
    }
    else {
      // No other clean-ups, run immediately
      preventor.runCleanUps();
    }

    Std.out(() -> cleanUps.size() + " clean-ups scheduled");
  }

  private static void runPreventorCleanUps(
      @NotNull ClassLoaderLeakPreventor preventor,
      @NotNull AtomicInteger remainingCount,
      @NotNull AtomicBoolean isUsed) {
    if (isUsed.compareAndSet(false, true)) {
      runPreventorCleanUps(preventor, remainingCount);
    }
  }

  private static void runPreventorCleanUps(
      @NotNull ClassLoaderLeakPreventor preventor,
      @NotNull AtomicInteger remainingCount) {
    if (remainingCount.decrementAndGet() == 0) {
      preventor.runCleanUps();
    }
  }

  private void register(@NotNull CleanUp cleanUp) {
    synchronized (lock) {
      if (!lock.get()) {
        cleanUps.add(cleanUp);
        return;
      }
    }
    throw new IllegalStateException("The clean-up list is not modifiable outside of the factory call");
  }

  private void register(@NotNull Consumer<Runnable> callback) {
    ClassLoaderLeakPreventor preventor = this.preventor;

    register((executorService, remainingCount) -> executorService.execute(
        () -> {
          AtomicBoolean isUsed = new AtomicBoolean();

          try {
            Runnable runnable = () -> executorService.execute(
                () -> runPreventorCleanUps(preventor, remainingCount, isUsed));

            callback.accept(() -> DO_IN_LEAK_SAFE_CLASS_LOADER.accept(preventor, runnable));
          }
          catch (Throwable t) {
            throwIfFatal(t);

            Std.err("Error during clean-up");
            Std.err(t);

            runPreventorCleanUps(preventor, remainingCount, isUsed);
          }
        }));
  }

  private void register(@NotNull Duration delay, @NotNull Runnable callback) {
    ClassLoaderLeakPreventor preventor = this.preventor;

    register((executorService, remainingCount) -> executorService.schedule(
        () -> {
          try {
            callback.run();
          }
          catch (Throwable t) {
            throwIfFatal(t);

            Std.err("Error during clean-up");
            Std.err(t);
          }
          runPreventorCleanUps(preventor, remainingCount);
        },
        delay.toMillis(),
        TimeUnit.MILLISECONDS));
  }

  @SuppressWarnings("unchecked") // Enforcement on outbound parameter types is not feasible
  @Override
  public void accept(@Nullable Consumer<Runnable> callback) {
    register(
        checkIsInstance(callback, Consumer.class));
  }

  @Override
  public void accept(@Nullable Duration delay, @Nullable Runnable callback) {
    register(
        checkIsInstance(delay, Duration.class),
        checkIsInstance(callback, Runnable.class));
  }
}
