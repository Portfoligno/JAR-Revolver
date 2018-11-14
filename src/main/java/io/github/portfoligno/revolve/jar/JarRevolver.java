package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import io.github.portfoligno.revolve.jar.function.ThrowingRunnable;
import org.jetbrains.annotations.NotNull;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventorFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.github.portfoligno.revolve.jar.ErrorHelper.*;
import static io.github.portfoligno.revolve.jar.FileWatcher.toAbsolutePath;

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
      JarRevolverContext @NotNull [] contextHolder) {
    JarRevolverContext context = null;

    try {
      JarClassLoader classLoader = new JarClassLoader(path);
      ClassLoaderLeakPreventor preventor = leakPreventorFactory.newLeakPreventor(classLoader);
      preventor.runPreClassLoaderInitiators();

      Object main = classLoader.loadMainClass().getConstructor().newInstance();
      AtomicBoolean lock = new AtomicBoolean();
      context = new JarRevolverContext(preventor, lock, new ArrayList<>());

      @SuppressWarnings("unchecked") // Enforcement on outbound parameter types is not feasible
      Object instance = handler instanceof Supplier && main instanceof BiFunction ?
          ((BiFunction) main).apply(((Supplier) handler).get(), context) :
          ((Function) main).apply(context);
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
      if (context != null) {
        context.scheduleCleanUps(executorService);
      }
      return;
    }
    JarRevolverContext lastContext = contextHolder[0];

    if (lastContext != null) {
      lastContext.scheduleCleanUps(executorService);
    }
    contextHolder[0] = context;
  }

  public void loadOrExitJvm(
      @NotNull Path path, @NotNull Consumer<Object> handler, @NotNull ThrowingRunnable postInitialization) {
    JarRevolverContext[] contextHolder = new JarRevolverContext[1];

    // Load for the first time
    Path absolutePath = toAbsolutePath(path);
    revolve(false, absolutePath, handler, contextHolder);

    // Run user defined post-initialization
    try {
      postInitialization.run();
    }
    catch (Throwable t) {
      throwIfFatal(t);

      Std.err("Error during post-initialization, shutting down in " + EXIT_DELAY + " ms");
      exitDelayed();
      return;
    }

    // Start watching for file updates
    FileWatcher
        .create(
            absolutePath,
            () -> revolve(true, absolutePath, handler, contextHolder))
        .start();
  }
}
