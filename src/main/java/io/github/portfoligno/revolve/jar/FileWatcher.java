package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import org.jetbrains.annotations.NotNull;

import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

class FileWatcher extends Thread {
  private final @NotNull Path directory;
  private final @NotNull Set<Path> fileNames;
  private final @NotNull Runnable action;

  private FileWatcher(@NotNull Path directory, @NotNull Set<Path> fileNames, @NotNull Runnable action) {
    this.directory = directory;
    this.fileNames = fileNames;
    this.action = action;
  }

  static @NotNull FileWatcher create(@NotNull Path path, @NotNull Runnable action) {
    return new FileWatcher(
        path.getParent(),
        new HashSet<>(Arrays.asList(
            path.getFileName(),
            append(path, ".reload").getFileName())),
        action);
  }

  static @NotNull Path toAbsolutePath(@NotNull Path path) {
    Path absolutePath = path.toAbsolutePath();

    if (absolutePath.getFileName() != null) {
      return absolutePath;
    }
    throw new IllegalArgumentException(path.toString());
  }

  static @NotNull Path append(@NotNull Path path, @NotNull String suffix) {
    return path.getParent().resolve(path.getFileName() + suffix);
  }

  @Override
  public void run() {
    try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
      WatchKey key = directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);

      while (true) {
        try {
          for (WatchEvent<?> e : watchService.take().pollEvents()) {
            if (fileNames.contains(((Path) e.context()).getFileName())) {
              action.run();
            }
          }
          key.reset();

          Thread.sleep(10);
        }
        catch (InterruptedException e) {
          Std.err(e);
        }
      }
    }
    catch (Exception e) {
      Std.err(e);
    }
  }
}
