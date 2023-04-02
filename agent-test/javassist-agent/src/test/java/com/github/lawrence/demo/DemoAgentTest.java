package com.github.lawrence.demo;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * @author : Lawrence
 * date  2023/3/26 21:59
 */
public class DemoAgentTest {

    @Test
    public void test() throws InterruptedException {
        new Student().eat();
        new Student().sleep();
    }


    public static class Student {
        void eat() throws InterruptedException {
            TimeUnit.MILLISECONDS.sleep(500);
            System.out.println("The students are eating.");
        }

        void sleep() throws InterruptedException {
            TimeUnit.SECONDS.sleep(1);
            System.out.println("The students are sleeping.");
            TimeUnit.MILLISECONDS.sleep(100);
        }
    }
}