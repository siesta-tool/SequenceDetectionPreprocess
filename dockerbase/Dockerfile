FROM openjdk:11 AS builder
RUN apt-get update && apt-get install -y gnupg2 curl &&\
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list &&\
echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list &&\
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add &&\
apt-get update && apt-get install -y sbt=1.10.0




RUN mkdir /app
COPY dockerbase/build.sbt /app/build.sbt
COPY src /app/src
COPY project /app/project

WORKDIR /app
RUN sbt clean assembly
RUN mv target/scala-2.12/app-assembly-0.1.jar preprocess.jar


FROM openjdk:11 AS execution
RUN apt-get update && apt-get install -y gnupg2 curl procps
RUN mkdir /app
WORKDIR /app
RUN curl -O https://archive.apache.org/dist/spark/spark-3.5.1/spark-3.5.1-bin-hadoop3.tgz &&\
tar xvf spark-3.5.1-bin-hadoop3.tgz && mv spark-3.5.1-bin-hadoop3/ /opt/spark && rm spark-3.5.1-bin-hadoop3.tgz
COPY --from=builder /app/preprocess.jar preprocess.jar

ENV s3accessKeyAws=minioadmin
ENV s3ConnectionTimeout=600000
ENV s3endPointLoc=http://minio:9000
ENV s3secretKeyAws=minioadmin

#ENTRYPOINT ["tail", "-f", "/dev/null"]
ENTRYPOINT ["/opt/spark/bin/spark-submit","--master","local[*]","preprocess.jar"]
CMD ["--logname","test","--delete_prev"]