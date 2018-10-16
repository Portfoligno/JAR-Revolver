package io.github.portfoligno.revolve.jar;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.util.jar.Attributes;

class JarClassLoader extends URLClassLoader {
  private final @NotNull URL url;

  private JarClassLoader(@NotNull URL url) {
    super(new URL[] { url }, JarClassLoader.class.getClassLoader());
    this.url = url;
  }

  JarClassLoader(@NotNull Path path) throws MalformedURLException {
    this(path.toUri().toURL());
  }

  private @NotNull String getMainClassName() throws IOException {
    URL u = new URL("jar", "", url + "!/");
    Attributes attr = ((JarURLConnection) u.openConnection()).getMainAttributes();

    if (attr != null) {
      String name = attr.getValue(Attributes.Name.MAIN_CLASS);

      if (name != null) {
        return name;
      }
    }
    throw new IllegalArgumentException("Missing the Main-Class attribute: " + attr);
  }

  @NotNull Class<?> loadMainClass() throws IOException, ClassNotFoundException {
    return loadClass(getMainClassName());
  }
}
