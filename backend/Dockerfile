FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

RUN sed -i 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list /etc/apt/sources.list.d/*.list 2>/dev/null; \
    sed -i 's|http://security.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list /etc/apt/sources.list.d/*.list 2>/dev/null; \
    apt-get update && apt-get install -y maven && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /root/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?><settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><name>aliyun</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml

COPY pom.xml .
RUN mvn dependency:resolve -B

COPY src ./src
# 说明：src/test 下现有 5 个测试类（TaskService / RedisSubscriberService /
# ReviewService / ImageService / RecognitionResultService）都能正常编译。
# 这里仍用 -Dmaven.test.skip=true 是为了让镜像构建更快、且不依赖测试环境；
# 若要把单测当作构建门禁，把本行改成 `mvn package -B` 即可。
RUN mvn package -Dmaven.test.skip=true -B

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
