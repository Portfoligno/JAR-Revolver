package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import io.github.portfoligno.revolve.jar.function.ThrowingRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventorFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.github.portfoligno.revolve.jar.ErrorHelper.*;

public class JarRevolver {
  private final @NotNull ClassLoaderLeakPreventorFactory leakPreventorFactory;
  private final @NotNull ScheduledExecutorService executorService;

  private JarRevolver(@NotNull ClassLoader classLoader, @NotNull ScheduledExecutorService executorService) {
    leakPreventorFactory = new ClassLoaderLeakPreventorFactory(classLoader);
    this.executorService = executorService;
  }

  public static @NotNull JarRevolver create() {
    return create(JarRevolver.class.getClassLoader(), Executors.newScheduledThreadPool(1));
  }

  public static @NotNull JarRevolver create(
      @NotNull ClassLoader classLoader, @NotNull ScheduledExecutorService executorService) {
    return new JarRevolver(classLoader, executorService);
  }

  private void revolve(
      boolean isInitialized, @NotNull Path path, @NotNull Consumer<Object> consumer,
      Entry<ClassLoaderLeakPreventor, List<Entry<Duration, Runnable>>> @NotNull [] cleanUpHolder) {
    List<Entry<Duration, Runnable>> cleanUps = new ArrayList<>();
    ClassLoaderLeakPreventor leakPreventor = null;

    try {
      JarClassLoader classLoader = new JarClassLoader(path);
      leakPreventor = leakPreventorFactory.newLeakPreventor(classLoader);
      leakPreventor.runPreClassLoaderInitiators();

      //noinspection unchecked
      Function main = (Function) classLoader.loadMainClass().getConstructor().newInstance();
      AtomicBoolean lock = new AtomicBoolean();

      //noinspection unchecked
      Object instance = main.apply((BiConsumer) (t, r) -> {
        synchronized (lock) {
          if (!lock.get()) {
            cleanUps.add(new SimpleImmutableEntry<>(
                checkIsInstance(t, Duration.class),
                checkIsInstance(r, Runnable.class)));
            return;
          }
        }
        throw new IllegalStateException("The clean-up list is not modifiable outside of the factory call");
      });
      lock.set(true);

      consumer.accept(instance);

      writeRevolverError(null, cleanUpHolder);
      Std.out(() -> "Instance revolved: " + instance);
    }
    catch (Throwable t) {
      throwIfFatal(t);

      writeRevolverError(t, cleanUpHolder);
      Std.err("Error during revolution");
      Std.err(t);

      if (!isInitialized) {
        Std.err("No instance available, shutting down in " + EXIT_DELAY + " ms");
        exitDelayed();
      }
      scheduleCleanUps(leakPreventor, cleanUps);
      return;
    }
    Entry<ClassLoaderLeakPreventor, List<Entry<Duration, Runnable>>> lastCleanUps = cleanUpHolder[0];

    if (lastCleanUps != null) {
      scheduleCleanUps(lastCleanUps.getKey(), lastCleanUps.getValue());
    }
    cleanUpHolder[0] = new SimpleImmutableEntry<>(leakPreventor, cleanUps);
  }

  private void scheduleCleanUps(@Nullable ClassLoaderLeakPreventor p, @NotNull List<Entry<Duration, Runnable>> tasks) {
    if (tasks.isEmpty()) {
      if (p != null) {
        p.runCleanUps();
      }
    }
    else {
      AtomicInteger remaining = new AtomicInteger(tasks.size());

      tasks.forEach(e -> {
        Runnable r = e.getValue();

        executorService.schedule(() -> {
          try {
            r.run();
          }
          catch (Throwable t) {
            throwIfFatal(t);

            Std.err("Error during clean-up");
            Std.err(t);
          }
          if (remaining.decrementAndGet() == 0 && p != null) {
            p.runCleanUps();
          }
        }, e.getKey().toMillis(), TimeUnit.MILLISECONDS);
      });
    }
    Std.out(() -> tasks.size() + " clean-ups scheduled");
  }

  public void loadOrExitJvm(
      @NotNull Path path, @NotNull Consumer<Object> consumer, @NotNull ThrowingRunnable postInitialization) {
    //noinspection unchecked
    Entry<ClassLoaderLeakPreventor, List<Entry<Duration, Runnable>>>[] cleanUpHolder = new Entry[1];
    revolve(false, path, consumer, cleanUpHolder);

    try {
      postInitialization.run();
    }
    catch (Throwable t) {
      throwIfFatal(t);

      Std.err("Error during post-initialization, shutting down in " + EXIT_DELAY + " ms");
      exitDelayed();
      return;
    }
    new FileWatcher(path, () -> revolve(true, path, consumer, cleanUpHolder)).start();
  }
}
