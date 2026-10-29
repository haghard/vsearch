# Akka HTTP / JVector / GraalVM 
A sample `akka-http` + `jvector` application compiled and run on GraalVM.

1. Download `Qwen3-Embedding-0.6B-Q8_0.gguf` or `Qwen3-Embedding-0.6B-f16.gguf` from https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/tree/main to `./local-models` 

2. `docker-compose up`

## Tech Stack
    - Java 25 + GraalVM native-image
    - ONNX Runtime  — embedded all-MiniLM-L6-v2 or granite-embedding-30m-english (384 dim) 
    - Jvector — HNSW index for vector search (sentence similarity search)  
    - akka-http — Http server
  
### Compile, Build and Run on JVM
    //export SBT_OPTS="-Xmx4G"
    sbt -J-Xmx2G assembly
    java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED -XX:+PrintCommandLineFlags -XX:NativeMemoryTracking=summary -Xmx512m -jar ./target/scala-2.13/vsearch-0.1.0.jar

### Compile, Build and Run on GraalJVM

    Download https://github.com/graalvm/graalvm-ce-builds/releases#release-graal-25.4.4.1.1

    export JAVA_HOME=/Users/myusrhaghard/Downloads/graalvm-community-25.4.4.1.1+1.1/Contents/Home

    sbt run //generartes content in native-image

    sbt "clean;nativeImage"

    ./target/native-image/vsearch-0.1.0
     or
     /usr/bin/time -l ./target/native-image/vsearch-0.1.0
###


### Request examples

    http POST :8080/index/reviews/save"?text=The best gym product ever"

    http :8080/index/reviews/search"?q=sport activities&limit=7"
    http :8080/index/reviews/search"?q=disgusting coffee"
    http :8080/index/reviews/search"?q=pricey products&limit=8"
    http :8080/index/reviews/search"?q=awesome coffee"

    http :8080/jcmd
###
