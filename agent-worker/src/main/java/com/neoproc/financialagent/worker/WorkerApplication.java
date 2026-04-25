package com.neoproc.financialagent.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Production entry point. Starts the Spring Boot application context,
 * which wires up the RabbitMQ consumer and the actuator endpoints.
 *
 * <p>For local dev without a broker, use {@link Agent#main} directly
 * ({@code mvn exec:java -Dexec.mainClass=...Agent -Dportal.id=mock-payroll ...}).
 */
@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
