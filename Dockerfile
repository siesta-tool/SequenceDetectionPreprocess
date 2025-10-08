FROM openjdk:11 AS builder
RUN apt-get update && apt-get install -y gnupg2 curl &&\
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list &&\
echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list &&\
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add &&\
apt-get update && apt-get install -y sbt=1.10.0

RUN mkdir /app

FROM builder AS preprocess


COPY src /app/src
COPY project /app/project
COPY build.sbt /app/build.sbt
RUN mkdir /app/input
RUN mkdir /app/output

WORKDIR /app
RUN sbt clean assembly
RUN mv target/scala-2.12/sequencedetectionpreprocess-assembly-3.0.0.jar preprocess.jar

FROM openjdk:11 AS execution
RUN apt-get update && apt-get install -y gnupg2 curl procps

RUN curl -O https://archive.apache.org/dist/spark/spark-3.5.4/spark-3.5.4-bin-hadoop3.tgz &&\
tar xvf spark-3.5.4-bin-hadoop3.tgz && mv spark-3.5.4-bin-hadoop3/ /opt/spark && rm spark-3.5.4-bin-hadoop3.tgz

RUN mkdir /app
WORKDIR /app
RUN mkdir /tmp/spark-events
COPY --from=preprocess /app/preprocess.jar /app/preprocess.jar
COPY --from=preprocess /app/input /app/input
COPY --from=preprocess /app/output /app/output

CMD ["tail","-f","/dev/null"]

