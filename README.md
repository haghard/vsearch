# Akka HTTP / JVector / GraalVM 
Example akka http project with jvector compiled with GraalVM.

  
### Compile, Build and Run
    
    sbt run

    sbt clean;nativeImage

    ./target/native-image/vsearch
###


### Request examples
    http POST :8080/index/reviews/save"?text=The best product ever"
    http :8080/index/reviews/search"?q=disgusting coffee"
    http :8080/index/reviews/search"?q=pricey products&limit=8"
    http :8080/index/reviews/search"?q=gym goods&limit=7"
###

                                  
