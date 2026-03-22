package com.lawrence.test.javassist._03;

public class OtherClassC {

    void c() {
        try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("c......");
        c1();
    }

    void c1() {
        try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("c1......");
    }
}
