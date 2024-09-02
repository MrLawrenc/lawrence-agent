package com.lawrence.test.javassist._02;

import java.util.concurrent.TimeUnit;

//-javaagent:H:\Projects\JavaProject\agent\simple-javassist-agent\build\libs\simple-javassist-agent-1.0-SNAPSHOT_.Javassist02WriteJavaAgent-BETA.jar={\"key\":\"value\"}
public class Javassist02WriteJavaAgent_Test {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Start Main..........");
        new Javassist02WriteJavaAgent_Test().sleepOneSeconds();
        System.out.println("done==>");
    }

    void sleepOneSeconds() throws InterruptedException {
        TimeUnit.SECONDS.sleep(1);
    }
}
