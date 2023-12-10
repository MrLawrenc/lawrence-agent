package org.example;

//-javaagent:/Users/lawrence/Projects/agent-all/javassist-agent/build/libs/javassist-agent-1.0-SNAPSHOT.jar=/Users/lawrence/Projects/agent-all/agent-test/src/main/resources/agent.properties // Press ⇧ twice to open the Search Everywhere dialog and type `show whitespaces`,
// then press Enter. You can now see whitespace characters in your code.
public class Main {
    public static void main(String[] args) {
        // Press ⌥⏎ with your caret at the highlighted text to see how
        // IntelliJ IDEA suggests fixing it.
        System.out.printf("Hello and welcome!");

        // Press ⌃R or click the green arrow button in the gutter to run the code.
        for (int i = 1; i <= 5; i++) {

            // Press <no shortcut> to start debugging your code. We have set one breakpoint
            // for you, but you can always add more by pressing ⌘F8.
            System.out.println("i = " + i);
        }
    }
}