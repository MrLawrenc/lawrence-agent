package com.lawrence.test.javassist._03;

public class OrderService {

    private final UserRepository userRepository = new UserRepository();
    private final PaymentRepository paymentRepository = new PaymentRepository();

    private void notifyUser(String userId) throws InterruptedException {
        Thread.sleep(15);
        System.out.println("notify user: " + userId);
    }

    public String createOrder(String userId) throws InterruptedException {
        Thread.sleep(20);
        String user = userRepository.findUser(userId);
        String payment = paymentRepository.processPayment(userId, 100);
        notifyUser(userId);
        return "Order created for " + user + ", payment: " + payment;
    }
}
