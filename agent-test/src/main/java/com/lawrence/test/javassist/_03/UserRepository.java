package com.lawrence.test.javassist._03;

public class UserRepository {

    public String findUser(String userId) throws InterruptedException {
        Thread.sleep(50);
        System.out.println("query user: " + userId);
        return "User[" + userId + "]";
    }

    public void updateUser(String userId) throws InterruptedException {
        Thread.sleep(30);
        System.out.println("update user: " + userId);
    }
}
