package com.lawrence.test.javassist._03;

public class OtherClassB {

    void b() {
        try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("b......");
        new OtherClassC().c();
    }
}
