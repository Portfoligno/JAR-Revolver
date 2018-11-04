package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.portfoligno.revolve.jar.FileWatcher.append;

class ErrorHelper {
  static final long EXIT_DELAY = 3600;

  static <T> T checkIsInstance(@Nullable Object reference, @NotNull Class<T> type) {
    if (reference != null) {
      return type.cast(reference);
    }
    throw new NullPointerException("Expected an instance of " + type.getName());
  }

  static void throwIfFatal(@NotNull Throwable t) {
    if (t instanceof ThreadDeath || t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
      throw (Error) t;
    }
  }

  static void writeRevolverError(@NotNull Path jarPath, @Nullable Throwable t) {
    try {
      Path file = append(jarPath, ".error");

      if (t != null) {
        try (PrintWriter writer = new PrintWriter(file.toFile())) {
          t.printStackTrace(writer);
        }
      }
      else {
        Files.deleteIfExists(file);
      }
    }
    catch (Exception e) {
      Std.err(e);
    }
  }

  static void exitDelayed() {
    try {
      Thread.sleep(EXIT_DELAY);
    }
    catch (InterruptedException e) {
      Std.err(e);
    }
    System.exit(76);
  }
}
