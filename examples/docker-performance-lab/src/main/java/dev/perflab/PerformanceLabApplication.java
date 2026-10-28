package dev.perflab;

import java.nio.file.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PerformanceLabApplication {
    public static void main(String[] args) { SpringApplication.run(PerformanceLabApplication.class,args); }
    @Bean ApplicationRunner ready() { return args->Files.writeString(Path.of("/tmp/lab-ready"),"ready"); }
}
