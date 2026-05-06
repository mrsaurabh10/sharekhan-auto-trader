#!/usr/bin/env sh
set -eu

JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx384m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/dumps -Xlog:gc*:file=/app/logs/gc.log:utctime}"

if [ "${ENABLE_DEBUG:-false}" = "true" ]; then
  JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
fi

if [ "${ENABLE_JMX:-false}" = "true" ]; then
  JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote"
  JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.port=${JMX_PORT:-9010}"
  JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.rmi.port=${JMX_PORT:-9010}"
  JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.local.only=false"
  JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.authenticate=${JMX_AUTHENTICATE:-false}"
  JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.ssl=${JMX_SSL:-false}"
  JAVA_OPTS="$JAVA_OPTS -Djava.rmi.server.hostname=${JMX_RMI_HOSTNAME:-127.0.0.1}"
fi

exec java $JAVA_OPTS -jar /app/app.jar "$@"
