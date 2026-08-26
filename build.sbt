name := "vsearch"

organization := "haghard"
version := "0.1.0"
scalaVersion := "2.13.18"

val AkkaVersion = "2.8.2"
val akkaHttpVersion = "10.5.2"

//https://github.com/com-lihaoyi/Ammonite/releases
val AmmoniteVersion = "3.0.9"

//export JAVA_HOME=/Users/haghard/Downloads/graalvm-jdk-25.0.4+7.1/Contents/Home
val requiredJvmVersion = "25"

initialize := {
  val _ = initialize.value
  val current = sys.props("java.specification.version")
  if (current != requiredJvmVersion)
    sys.error(s"Java $requiredJvmVersion is required for this project. Found $current instead.")
}

//Source compatibility (Language features) and Target compatibility (Bytecode version)
javacOptions ++= Seq("-source", requiredJvmVersion, "-target", requiredJvmVersion)

Compile / scalacOptions ++= Seq(
  "-Xsource:3",  //It enables Scala 3 specific syntax (like * for wildcards instead of _) and changes certain compiler behaviors to match Scala 3’s stricter rules.
  s"-release:$requiredJvmVersion",
  "-Wconf:cat=other-match-analysis:error", // report incomplete case match as error
  "-Wconf:cat=other-pure-statement:silent", // silence "unused value of type [???] (add `: Unit` to discard silently)"
  "-Wnonunit-statement",
  "-Ylog-classpath", //log classpath
  "-feature",
  "-language:existentials",
  "-Xfatal-warnings", // Fail the compilation if there are any warnings.
  "-Wconf:cat=other-match-analysis:error" //Transform exhaustive warnings into errors.
)

//show javaOptions
javaOptions ++= Seq(
  "-XX:+PrintCommandLineFlags",
  "-XshowSettings:system",
  "-XX:+UseStringDeduplication",

  //heap never resizes
  "-Xms1G",
  "-Xmx1G",
  "-XX:+AlwaysPreTouch",
  "-XX:-UseAdaptiveSizePolicy",

  "-XX:+UseG1GC",      //with heaps >4GB
  //"-XX:+UseParallelGC",  //with heaps < 4GB
  //"-XX:+UseZGC",       //apps that require sub-millisecond GC pauses, with gigantic (terabyte range) heaps
  "-XX:NativeMemoryTracking=summary", //detail|summary
  "-XX:MaxDirectMemorySize=128m", //Will get an error if allocate more mem for direct byte buffers
  "-XX:+UseCompactObjectHeaders", //In a standard 64-bit JVM, every object has a header that contains metadata. The JVM compresses this metadata into a single 8-byte header.
  "-XX:ActiveProcessorCount=8",

  "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
  "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-exports", "jdk.unsupported/sun.misc=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",

  "--add-opens","jdk.compiler/com.sun.tools.javac=ALL-UNNAMED",
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens",  "java.base/java.io=ALL-UNNAMED",
  "--add-opens", "java.base/java.util=ALL-UNNAMED",
  "--add-opens", "java.base/java.nio=ALL-UNNAMED",
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",

  "--add-modules", "jdk.incubator.vector",
  "--enable-native-access=ALL-UNNAMED"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "org.wvlet.airframe" %% "airframe-ulid" % "2026.2.2",

  "org.rocksdb" % "rocksdbjni" % "10.10.1.1",

  "com.microsoft.onnxruntime" % "onnxruntime" % "1.29.0",
  "ai.djl.huggingface" % "tokenizers" % "0.36.0",
  "com.opencsv" % "opencsv" % "5.12.0",

  //"org.tribuo" % "tribuo-clustering-hdbscan" % "4.3.2",

  ("io.github.jbellis" % "jvector" % "4.0.0-rc.9").exclude("org.slf4j", "slf4j-api"),

  "ch.qos.logback" % "logback-classic" % "1.6.3",
  //"com.lihaoyi" % "ammonite" % AmmoniteVersion % "test" cross CrossVersion.full
)

Compile / mainClass := Some("com.github.haghard.vsearch.Program")

//for ammonite
//run / fork := false

// ammonite repl
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue


/*assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x if x.endsWith(".proto") => MergeStrategy.discard
  case x if x.endsWith("io.netty.versions.properties") => MergeStrategy.first
  case PathList("reference.conf") => MergeStrategy.concat
  case PathList("application.conf") => MergeStrategy.concat
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", "native-image", _*) => MergeStrategy.first
  case PathList("META-INF", "versions", _*) => MergeStrategy.first
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", _*) => MergeStrategy.first
  case _ => MergeStrategy.first
}*/

/*
graalVMNativeImageOptions ++= Seq(
  "--enable-monitoring=jfr,jvmstat,jcmd,threaddump,heapdump,nmt",
  "--enable-preview",
  "--add-modules=jdk.incubator.vector",
  "-H:+UnlockExperimentalVMOptions",
  "--verbose",
  "--no-fallback",
  "-J--enable-native-access=ALL-UNNAMED",
  "--report-unsupported-elements-at-runtime",
  "--enable-url-protocols=http,https",
  "-H:IncludeResources=.*\\.properties", // ── Resources to bundle in the binary ──
  //"-H:IncludeResources=models/.*", // ── Resources to bundle in the binary ──
  "-H:IncludeResources=jina-embeddings-v2-base-en/.*",
  "-H:ResourceConfigurationFiles=" + baseDirectory.value / "conf-agent" / "resource-config.json",
  "-H:ReflectionConfigurationFiles=" + baseDirectory.value / "conf-agent" / "reflect-config.json",
  "-J-Xmx3g",
  "--gc=serial", //"--gc=G1",
  "-R:MinHeapSize=256m","-R:MaxHeapSize=512m", //bakes defaults into binary
  //"-J-XX:+UseG1GC",
  "--enable-all-security-services",
  "--emit build-report",
  "--initialize-at-build-time",
  // ── ONNX Runtime: JNI-heavy, must init at runtime ──
  "--initialize-at-run-time=ai.onnxruntime",
  "--initialize-at-run-time=ai.djl",
  "--initialize-at-run-time=io.github.jbellis.jvector",
  // ── Logback: runtime init to avoid build-time logging context issues ──
  //"--initialize-at-run-time=ch.qos.logback",
  "-O2", // ── Reduce binary size ──
  "--initialize-at-run-time=akka.protobuf.DescriptorProtos,com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder,com.oracle.truffle.js.scriptengine.GraalJSEngineFactory," +
  "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder"
)

*/

// clean;nativeImage
// ./target/native-image/akka-graal-native


Compile / run / fork := true
Compile / run / javaOptions += "-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image"


// GraalVM native image build
enablePlugins(NativeImagePlugin)
nativeImageJvm := "graalvm-community"
//https://github.com/graalvm/container/pkgs/container/native-image-community
//https://medium.com/graalvm/graalvm-25-3-is-here-41641acebfaf
//https://github.com/graalvm/graalvm-demos
nativeImageVersion := "25.0.2"// "21.0.2"

nativeImageOptions := Seq(
  //"-R:PrintFlags=", //(allowed categories: User, Expert, Debug). Default: None
  "--enable-monitoring=jfr,jvmstat,jcmd,threaddump,heapdump,nmt",
  "--no-fallback",
  "--verbose",
  "--install-exit-handlers",
  "--add-modules=jdk.incubator.vector",
  "--enable-native-access=ALL-UNNAMED",
  "-H:+VectorAPISupport",
  "-R:MinHeapSize=512m","-R:MaxHeapSize=512m", //bakes defaults into binary
  //"-H:IncludeResources=.*librocksdbjni-.*",
  //"-Ob",
  "-R:ActiveProcessorCount=8",
  "--initialize-at-run-time=ch.qos.logback"
)


NativeImage / mainClass := Some("com.github.haghard.vsearch.Program")
// silence warnings for these keys (used in dynamic task)
Global / excludeLintKeys ++= Set(nativeImageJvm, nativeImageVersion)

scalafmtOnCompile := true

addCommandAlias("c", "compile")
addCommandAlias("r", "reload")


//export JAVA_HOME=/Users/haghard/Downloads/graalvm-jdk-25.0.4+7.1/Contents/Home
//export PATH=$JAVA_HOME/bin:$PATH


/*

Agentis Memory
  https://habr.com/ru/articles/1018784/
  https://scrobot.substack.com/p/agentis-memory-redis-compatible-store
  https://github.com/scrobot/agentis-memory (GraalVM, jvector)/ Embeddings
  all-MiniLM-L6-v2 via ONNX Runtime
    ## Tech Stack
- Java 26 + GraalVM native-image (single binary)
  - ONNX Runtime via Panama FFI — embedded all-MiniLM-L6-v2 (~80MB, 384 dim)
  - jvector (DataStax, Apache 2.0) — HNSW index for vector search
  - Java Vector API — SIMD-accelerated cosine similarity
  - Netty — TCP server for RESP protocol

*/