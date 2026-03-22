package com.lawrence.test.javassist._03;

public class OtherClassA {
    void a() {
        System.out.println("a......");
        a1();
        a2();
    }

    void a1() {
        try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("a1......");
    }

    void a2() {
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("a2......");
    }
}
