package io.github.portfoligno.revolve.jar.function;

@FunctionalInterface
public interface ThrowingRunnable {
  void run() throws Throwable;
}
