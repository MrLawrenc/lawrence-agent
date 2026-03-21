package com.lawrence.test.javassist._03.bytesforce;

import java.util.concurrent.TimeUnit;

public class TestBD {
    public void test() {
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Test BD");
    }
}
