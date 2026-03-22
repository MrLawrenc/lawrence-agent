package com.lawrence.test.javassist._03;

public class ExceptionService {

    public String process(boolean throwErr) throws InterruptedException {
        Thread.sleep(30);
        String result = step1();
        if (throwErr) {
            step2WithError();
        }
        return result;
    }

    private String step1() throws InterruptedException {
        Thread.sleep(25);
        System.out.println("step1 done");
        return "ok";
    }

    private void step2WithError() throws InterruptedException {
        Thread.sleep(10);
        throw new RuntimeException("simulated error in step2");
    }
}
