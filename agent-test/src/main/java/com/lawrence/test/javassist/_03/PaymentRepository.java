package com.lawrence.test.javassist._03;

public class PaymentRepository {

    public String processPayment(String userId, int amount) throws InterruptedException {
        Thread.sleep(80);
        System.out.println("process payment: userId=" + userId + " amount=" + amount);
        validateBalance(userId, amount);
        return "PAY-" + userId + "-" + amount;
    }

    private void validateBalance(String userId, int amount) throws InterruptedException {
        Thread.sleep(40);
        System.out.println("validate balance: " + userId);
    }
}
