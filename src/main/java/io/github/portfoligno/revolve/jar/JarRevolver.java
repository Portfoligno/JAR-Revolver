package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import io.github.portfoligno.revolve.jar.function.ThrowingRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventorFactory;

import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
      boolean isInitialized, @NotNull Path path, @NotNull Consumer<Object> handler,
      Entry<ClassLoaderLeakPreventor, List<CleanUp>> @NotNull [] cleanUpHolder) {
    List<CleanUp> cleanUps = new ArrayList<>();
    ClassLoaderLeakPreventor leakPreventor = null;

    try {
      JarClassLoader classLoader = new JarClassLoader(path);
      leakPreventor = leakPreventorFactory.newLeakPreventor(classLoader);
      leakPreventor.runPreClassLoaderInitiators();

      Object main = classLoader.loadMainClass().getConstructor().newInstance();
      AtomicBoolean lock = new AtomicBoolean();
      Object registry = new Registry(lock, cleanUps);

      //noinspection unchecked
      Object instance = handler instanceof Supplier && main instanceof BiFunction ?
          ((BiFunction) main).apply(((Supplier) handler).get(), registry) :
          ((Function) main).apply(registry);
      lock.set(true);

      handler.accept(instance);

      writeRevolverError(path, null);
      Std.out(() -> "Instance revolved: " + instance);
    }
    catch (Throwable t) {
      throwIfFatal(t);

      writeRevolverError(path, t);
      Std.err("Error during revolution");
      Std.err(t);

      if (!isInitialized) {
        Std.err("No instance available, shutting down in " + EXIT_DELAY + " ms");
        exitDelayed();
      }
      scheduleCleanUps(leakPreventor, cleanUps);
      return;
    }
    Entry<ClassLoaderLeakPreventor, List<CleanUp>> lastCleanUps = cleanUpHolder[0];

    if (lastCleanUps != null) {
      scheduleCleanUps(lastCleanUps.getKey(), lastCleanUps.getValue());
    }
    cleanUpHolder[0] = new SimpleImmutableEntry<>(leakPreventor, cleanUps);
  }

  private void scheduleCleanUps(@Nullable ClassLoaderLeakPreventor p, @NotNull List<CleanUp> cleanUps) {
    if (!cleanUps.isEmpty()) {
      AtomicInteger remaining = new AtomicInteger(cleanUps.size());

      // ClassLoaderLeakPreventor#runCleanUps will be called after the last clean-up
      cleanUps.forEach(f -> f.accept(p, executorService, remaining));
    }
    else if (p != null) {
      // No other clean-ups, run immediately
      p.runCleanUps();
    }

    Std.out(() -> cleanUps.size() + " clean-ups scheduled");
  }


  public void loadOrExitJvm(
      @NotNull Path path, @NotNull Consumer<Object> handler, @NotNull ThrowingRunnable postInitialization) {
    //noinspection unchecked
    Entry<ClassLoaderLeakPreventor, List<CleanUp>>[] cleanUpHolder = new Entry[1];
    revolve(false, path, handler, cleanUpHolder);

    try {
      postInitialization.run();
    }
    catch (Throwable t) {
      throwIfFatal(t);

      Std.err("Error during post-initialization, shutting down in " + EXIT_DELAY + " ms");
      exitDelayed();
      return;
    }
    new FileWatcher(path, () -> revolve(true, path, handler, cleanUpHolder)).start();
  }
}
