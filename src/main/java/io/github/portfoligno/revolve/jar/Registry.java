package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.github.portfoligno.revolve.jar.ErrorHelper.checkIsInstance;
import static io.github.portfoligno.revolve.jar.ErrorHelper.throwIfFatal;

class Registry implements Consumer<Consumer<Runnable>>, BiConsumer<Duration, Runnable> {
  private final @NotNull AtomicBoolean lock;
  private final @NotNull List<CleanUp> entries;

  Registry(@NotNull AtomicBoolean lock, @NotNull List<CleanUp> entries) {
    this.lock = lock;
    this.entries = entries;
  }

  private static void runPreventorCleanUps(
      @Nullable ClassLoaderLeakPreventor preventor,
      @NotNull AtomicInteger remainingCount,
      @NotNull AtomicBoolean isUsed) {
    if (isUsed.compareAndSet(false, true)) {
      runPreventorCleanUps(preventor, remainingCount);
    }
  }

  private static void runPreventorCleanUps(
      @Nullable ClassLoaderLeakPreventor preventor,
      @NotNull AtomicInteger remainingCount) {
    if (remainingCount.decrementAndGet() == 0 && preventor != null) {
      preventor.runCleanUps();
    }
  }

  private void register(@NotNull CleanUp cleanUp) {
    synchronized (lock) {
      if (!lock.get()) {
        entries.add(cleanUp);
        return;
      }
    }
    throw new IllegalStateException("The clean-up list is not modifiable outside of the factory call");
  }

  private void register(@NotNull Consumer callback) {
    register((preventor, executorService, remainingCount) -> executorService.submit(
        () -> {
          AtomicBoolean isUsed = new AtomicBoolean();

          try {
            //noinspection unchecked
            callback.accept((Runnable) () -> runPreventorCleanUps(preventor, remainingCount, isUsed));
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
    register((preventor, executorService, remainingCount) -> executorService.schedule(
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
