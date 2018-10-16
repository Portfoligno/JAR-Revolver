package io.github.portfoligno.revolve.jar;

import io.github.portfoligno.log.std.Std;
import org.jetbrains.annotations.NotNull;

import java.nio.file.*;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

class FileWatcher extends Thread {
  private final @NotNull Path path;
  private final @NotNull Runnable action;

  FileWatcher(@NotNull Path path, @NotNull Runnable action) {
    this.path = path;
    this.action = action;
  }

  @Override
  public void run() {
    try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
      WatchKey key = path.toAbsolutePath().getParent().register(watchService, ENTRY_CREATE, ENTRY_MODIFY);

      while (true) {
        try {
          for (WatchEvent<?> e : watchService.take().pollEvents()) {
            if (e.context().equals(path)) {
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
