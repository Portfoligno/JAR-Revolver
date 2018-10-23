package io.github.portfoligno.revolve.jar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

@FunctionalInterface
interface CleanUp {
  void accept(
      @Nullable ClassLoaderLeakPreventor preventor,
      @NotNull ScheduledExecutorService executorService,
      @NotNull AtomicInteger remainingCount);
}
