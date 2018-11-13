package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderPreMortemCleanUp;
import se.jiderhamn.classloader.leak.prevention.Logger;
import se.jiderhamn.classloader.leak.prevention.PreClassLoaderInitiator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.github.portfoligno.revolve.jar.ErrorHelper.checkIsInstance;
import static io.github.portfoligno.revolve.jar.ErrorHelper.throwIfFatal;

class Registry implements Consumer<Consumer<Runnable>>, BiConsumer<Duration, Runnable> {
  private static final BiConsumer<ClassLoaderLeakPreventor, Runnable> doInLeakSafeClassLoader =
      getDoInLeakSafeClassLoader();

  private static abstract class MethodNameMarker extends ClassLoaderLeakPreventor {
    public MethodNameMarker(
        ClassLoader leakSafeClassLoader, ClassLoader classLoader, Logger logger,
        Collection<PreClassLoaderInitiator> preClassLoaderInitiators,
        Collection<ClassLoaderPreMortemCleanUp> cleanUps) {
      super(leakSafeClassLoader, classLoader, logger, preClassLoaderInitiators, cleanUps);
    }

    @Override
    protected abstract void doInLeakSafeClassLoader(Runnable runnable);
  }

  @SuppressWarnings("OptionalGetWithoutIsPresent")
  private static BiConsumer<ClassLoaderLeakPreventor, Runnable> getDoInLeakSafeClassLoader() {
    try {
      Method method = ClassLoaderLeakPreventor.class.getDeclaredMethod(
          Stream
              .of(MethodNameMarker.class.getDeclaredMethods())
              .filter(m -> !m.isSynthetic())
              .findFirst()
              .get()
              .getName(),
          Runnable.class);
      method.setAccessible(true);

      return (preventor, runnable) -> {
        try {
          method.invoke(preventor, runnable);
        }
        catch (IllegalAccessException | InvocationTargetException e) {
          throw new RuntimeException(e);
        }
      };
    }
    catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

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

  private void register(@NotNull Consumer<Runnable> callback) {
    register((preventor, executorService, remainingCount) -> executorService.execute(
        () -> {
          AtomicBoolean isUsed = new AtomicBoolean();

          try {
            Runnable runnable = () -> executorService.execute(
                () -> runPreventorCleanUps(preventor, remainingCount, isUsed));

            callback.accept(preventor == null ?
                runnable : () -> doInLeakSafeClassLoader.accept(preventor, runnable));
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
