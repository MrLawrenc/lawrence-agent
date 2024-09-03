package com.lawrence.test.javassist._03;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

//javax.servlet.http.HttpServlet
//jakarta.servlet.http.HttpServlet
//-javaagent:H:\Projects\JavaProject\agent\javassist-agent\build\libs\javassist-agent-1.0-SNAPSHOT_.AttachMain-202409032049_50.jar=H:\Projects\JavaProject\agent\agent-test\src\main\resources\03\agent.properties
@SpringBootApplication
@RestController()
public class Servlet03Agent {


    public static void main(String[] args) {
        SpringApplication.run(Servlet03Agent.class, args);
    }


    @GetMapping("/hello")
    public String hello(
            @RequestParam("param1") String param1
    ) {
        return "hello " + param1;
    }
}
