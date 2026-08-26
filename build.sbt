name := "vsearch"

organization := "haghard"
scalaVersion := "2.13.18"
version := "0.1.0"

//Last Apache2 version https://doc.akka.io/reference/release-notes/2023-05-16-akka-23.5-released.html
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

  //to inject product reviews internally, can be removed.
  "com.opencsv" % "opencsv" % "5.12.0",

  ("io.github.jbellis" % "jvector" % "4.0.0-rc.9").exclude("org.slf4j", "slf4j-api"),

  "ch.qos.logback" % "logback-classic" % "1.6.3",
  //"com.lihaoyi" % "ammonite" % AmmoniteVersion % "test" cross CrossVersion.full
)

Compile / mainClass := Some("com.github.haghard.vsearch.Program")

mainClass := Some("com.github.haghard.vsearch.Program")

//for ammonite
run / fork := true

// ammonite repl
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

assemblyMergeStrategy := {
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
}

assemblyJarName := s"${name.value}-${version.value}.jar"
//scalaBinaryVersion := "2.13" //3

Compile / run / fork := true
Compile / run / javaOptions += "-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image"

// GraalVM native image build
enablePlugins(NativeImagePlugin, JavaAppPackaging, BuildInfoPlugin)
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
  //"-R:MaxRAMPercentage=70",
  "-R:MaxRAM=450m", // Physical memory size (in bytes). By default, the value is queried from the OS/container during VM startup.
  "--gc=serial", //"--gc=G1" 
  //"-Ob",
  //"-O2", // ── Reduce binary size ──
  "-R:ActiveProcessorCount=8",
  "--initialize-at-run-time=ch.qos.logback"
)
nativeImageOutput := target.value / "native-image" / s"${name.value}-${version.value}"

NativeImage / mainClass := Some("com.github.haghard.vsearch.Program")
// silence warnings for these keys (used in dynamic task)
Global / excludeLintKeys ++= Set(nativeImageJvm, nativeImageVersion)

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "com.github.haghard.vsearch"
buildInfoOptions := Seq(BuildInfoOption.BuildTime)
buildInfoOptions += BuildInfoOption.BuildTime


scalafmtOnCompile := true

addCommandAlias("c", "compile")
addCommandAlias("r", "reload")
addCommandAlias("asm", "clean;assembly")

//export JAVA_HOME=/Users/haghard/Downloads/graalvm-jdk-25.0.4+7.1/Contents/Home
//export PATH=$JAVA_HOME/bin:$PATH


