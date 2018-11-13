plugins {
  maven
  `java-library`
}
tasks.withType<Wrapper> {
  gradleVersion = "4.10.2"
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
  jcenter()
  maven("https://jitpack.io")
}
dependencies {
  compileOnly("org.jetbrains:annotations:16.0.3")
  implementation("io.github.portfoligno:std:1.2.1")
  implementation("se.jiderhamn.classloader-leak-prevention:classloader-leak-prevention-core:2.6.1")
}
