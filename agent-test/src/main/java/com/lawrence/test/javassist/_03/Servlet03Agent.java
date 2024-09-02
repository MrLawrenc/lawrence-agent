package com.lawrence.test.javassist._03;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

//javax.servlet.http.HttpServlet
//jakarta.servlet.http.HttpServlet
//H:\Projects\JavaProject\lagent\javassist-agent\build\libs\javassist-agent-1.0_0.01-SNAPSHOT.jar
@SpringBootApplication
@RestController()
public class Servlet03Agent {


    public static void main(String[] args) {
        SpringApplication.run(Servlet03Agent.class, args);
    }


    @GetMapping("/hello")
    public String hello() {
        return "hello";
    }
}
