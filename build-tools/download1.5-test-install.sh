#!/usr/bin/env bash

CRDIR="$(cd "`dirname "$0"`"; pwd)"
SPARK_NAME="${SPARK:-spark-1.5.2}"

SPARK_DIR_NAME="$SPARK_NAME"-bin-hadoop2.4

# Download Spark
wget -nc http://archive.apache.org/dist/spark/"$SPARK_NAME"/"$SPARK_DIR_NAME".tgz
tar -xzf "$SPARK_DIR_NAME".tgz

export SPARK_HOME="$PWD"/"$SPARK_DIR_NAME"
echo $SPARK_HOME

exec "$CRDIR"/test-install.sh -Pspark-1.5 "$@"