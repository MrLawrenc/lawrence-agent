# A development-only JDK image, also used by the repository's existing local fixtures.
FROM gradle:8.14.3-jdk21
USER root
WORKDIR /opt/lab
COPY build/install/performance-lab/lib/ /opt/lab/lib/
COPY build/monitor-cli-all.jar /opt/monitor/monitor-cli.jar
ENTRYPOINT ["java", "-Xms64m", "-Xmx160m", "-XX:ReservedCodeCacheSize=64m", "-XX:+EnableDynamicAgentLoading", "-Djava.rmi.server.hostname=127.0.0.1", "-cp", "/opt/lab/lib/*", "dev.perflab.PerformanceLabApplication"]
