package com.lawrence.test.javassist._02;

import java.util.concurrent.TimeUnit;

//-javaagent:H:\Projects\JavaProject\lawrence-agent\agent-test\build\libs\agent-test-1.0_Javassist02-Javassist02.jar={\"key\":\"value\"}
public class Javassist02WriteJavaAgent_Test {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Start Main..........");
        new Javassist02WriteJavaAgent_Test().sleepOneSeconds();
    }

    void sleepOneSeconds() throws InterruptedException {
        TimeUnit.SECONDS.sleep(1);
    }
}
