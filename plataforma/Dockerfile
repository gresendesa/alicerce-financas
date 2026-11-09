# Imagem da aplicação financeiro. Os testes rodam em ci.sh; aqui só se empacota.
FROM eclipse-temurin:21.0.12.1_1-jdk AS build
WORKDIR /w
COPY . .
RUN ./gradlew --no-daemon -q :financeiro:app:bootJar \
 && java -Djarmode=tools -jar financeiro/app/build/libs/financeiro.jar extract --layers --launcher --destination /extraido

FROM eclipse-temurin:21.0.12.1_1-jre
RUN useradd --system --uid 10001 financeiro
WORKDIR /app
COPY --from=build /extraido/dependencies/ ./
COPY --from=build /extraido/spring-boot-loader/ ./
COPY --from=build /extraido/snapshot-dependencies/ ./
COPY --from=build /extraido/application/ ./
USER financeiro
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
