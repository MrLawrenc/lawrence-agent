package com.lawrence.test.javassist._01;

import javassist.*;

import java.lang.reflect.Method;

//jdk17 add VM option: --add-opens java.base/java.lang=ALL-UNNAMED
public class Javassist01ChangeClass {
    public static void main(String[] args) throws Exception {
        ClassPool pool = ClassPool.getDefault();
        //创建类
        CtClass ctClass = pool.makeClass("org.test.ClassSourceFile_Javassist");
        //创建字段name
        CtField nameField = new CtField(pool.get("java.lang.String"), "name", ctClass);
        //设置为private
        nameField.setModifiers(Modifier.PRIVATE);
        ctClass.addField(nameField);

        //添加getName和setName方法
        ctClass.addMethod(CtNewMethod.getter("getName", nameField));
        ctClass.addMethod(CtNewMethod.setter("setName", nameField));

        //增加无参构造方法：其中 $0 表示 this，$1 表示参数
        CtConstructor noArgsCons = new CtConstructor(new CtClass[]{}, ctClass);
        noArgsCons.setBody("{$0.name=\"default name\";}");
        ctClass.addConstructor(noArgsCons);

        // 增加有参构造方法
        CtConstructor hasArgsCons =
                new CtConstructor(new CtClass[]{pool.get("java.lang.String")}, ctClass);
        hasArgsCons.setBody("{$0.name=$1;}");
        ctClass.addConstructor(hasArgsCons);

        // 创建方法
        CtMethod method = new CtMethod(CtClass.voidType, "printName", new CtClass[]{}, ctClass);
        method.setBody("{System.out.println($0.name);}");
        ctClass.addMethod(method);

        method.insertBefore("System.out.println(\"Custom added bytecode methods.\");");

        // 生成类文件：可指定路径，默认为当前项目根目录
        // 指定为当前项目build/generate-class目录
        ctClass.writeFile("agent-test/build/generate-class");

        //新的字节码
        Class<?> newClz = ctClass.toClass();

        // 调用无参构造方法 创建类实例
        Object classSourceFile = newClz.getConstructor().newInstance();
        Method printName = classSourceFile.getClass().getMethod("printName");
        printName.invoke(classSourceFile);

        System.out.println("=================================================");
        // 调用有参构造方法 创建类实例
        classSourceFile = newClz.getConstructor(String.class).newInstance("lawrence");
        printName = classSourceFile.getClass().getMethod("printName");
        printName.invoke(classSourceFile);
    }
}
