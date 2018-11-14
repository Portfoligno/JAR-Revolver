package io.github.portfoligno.revolve.jar;

import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;
import se.jiderhamn.classloader.leak.prevention.ClassLoaderPreMortemCleanUp;
import se.jiderhamn.classloader.leak.prevention.Logger;
import se.jiderhamn.classloader.leak.prevention.PreClassLoaderInitiator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

class PreventorHelper {
  static final BiConsumer<ClassLoaderLeakPreventor, Runnable> DO_IN_LEAK_SAFE_CLASS_LOADER =
      getDoInLeakSafeClassLoader();

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
}
