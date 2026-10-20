FROM moalhaddar/docx-to-pdf-base:1.0.0

WORKDIR /project
COPY ./pom.xml .
RUN mvn verify clean -Dmaven.artifact.threads=8 --fail-never

COPY ./src ./src
RUN mvn package

RUN cp ./target/*.jar ./app.jar
RUN mvn clean
COPY ./fonts/ /usr/share/fonts/custom
RUN rm -rf ./src ./pom.xml
EXPOSE 8080

CMD ["java", "-jar", "./app.jar"]