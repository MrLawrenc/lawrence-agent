package com.lawrence.test.javassist._03;

import com.lawrence.test.javassist._03.com.bytesforce.TestBD;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

//javax.servlet.http.HttpServlet
//jakarta.servlet.http.HttpServlet
//-javaagent:H:\Projects\JavaProject\lawrence-agent\javassist-agent\build\libs\javassist-agent-1.0-SNAPSHOT.jar=agent.properties
@SpringBootApplication
@RestController()
public class Servlet03Agent {

    @Autowired
    private JdbcTemplate jdbcTemplate;


    public static void main(String[] args) {
        System.out.println(OtherClassA.class);
        SpringApplication.run(Servlet03Agent.class, args);
    }


    @GetMapping("/hello")
    public String hello(
            @RequestParam("param1") String param1
    ) throws InterruptedException {
        new OtherClassA().a();
        new OtherClassB().b();
        new TestBD().test();
        return "hello " + param1;
    }

    @GetMapping("/service")
    public String service(
            @RequestParam("userId") String userId
    ) throws InterruptedException {
        return new OrderService().createOrder(userId);
    }

    @GetMapping("/jdbc")
    public String jdbc() {
        List<Map<String, Object>> users = jdbcTemplate.queryForList("SELECT * FROM users");
        jdbcTemplate.update("INSERT INTO orders(user_id, product, amount) VALUES(?, ?, ?)", 1, "Monitor-Test", 1.0);
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("SELECT * FROM orders WHERE user_id = ?", 1);
        return "users=" + users.size() + ", orders=" + orders.size();
    }

    @GetMapping("/error")
    public String error(
            @RequestParam(value = "fail", defaultValue = "false") boolean fail
    ) {
        try {
            return new ExceptionService().process(fail);
        } catch (Exception e) {
            return "caught: " + e.getMessage();
        }
    }
}
