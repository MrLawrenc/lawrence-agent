package com.lawrence.test.javassist._02;

import javassist.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class Javassist02WriteJavaAgent {
//    public static void premain(String agentArgs, Instrumentation inst) {
//        System.out.println("Execute agent premain method[" + agentArgs + "].............");
//        inst.addTransformer(new ClassFileTransformer() {
//            @Override
//            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] byteCode) {
//                if (className.equals("com/lawrence/test/javassist/Javassist02WriteJavaAgent_Test")) {
//                    className = className.replaceAll("/", ".");
//                    System.out.println("match class:" + className);
//                    try {
//                        ClassPool cPool = new ClassPool(true);
//                        //获取该class对象
//                        CtClass cClass = cPool.get(className);
//                        //获取到对应的方法
//                        CtMethod cMethod = cClass.getDeclaredMethod("sleepOneSeconds");
//                        //通过insert相关方法在原方法前后插入逻辑
//                        cMethod.addLocalVariable("begin", CtClass.longType);
//                        cMethod.insertBefore(" begin = System.currentTimeMillis();");
//                        cMethod.addLocalVariable("end", CtClass.longType);
//                        cMethod.insertAfter("end = System.currentTimeMillis();");
//                        cMethod.insertAfter("System.out.println(\"cost: \"+(end - begin));");
//
//                        //输出新的字节码到指定文件夹
//                        cClass.writeFile("build/generate-class");
//                        return cClass.toBytecode();
//                    } catch (Throwable e) {
//                        System.err.println(e.getMessage());
//                    }
//                }
//                return byteCode;
//            }
//        });
//    }


    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("Execute agent premain method[" + agentArgs + "].............");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] byteCode) {
                if (className.equals("com/lawrence/test/javassist/Javassist02WriteJavaAgent_Test")) {
                    className = className.replaceAll("/", ".");
                    System.out.println("match class:" + className);
                    try {
                        ClassPool cPool = new ClassPool(true);
                        //获取该class对象
                        CtClass cClass = cPool.get(className);
                        String methodName = "sleepOneSeconds";
                        String newMethodName = methodName + "$agent";
                        //获取到对应的方法
                        CtMethod oldMethod = cClass.getDeclaredMethod(methodName);

                        CtMethod newMethod = CtNewMethod.copy(oldMethod, newMethodName, cClass, null);
                        cClass.addMethod(newMethod);
                        oldMethod.setBody("{" +
                                "long begin = System.currentTimeMillis();" +
                                "Object result;" +
                                "try{" +
                                "   result = ($r)$0."+newMethodName+"($$);" +
                                "}catch(java.lang.Throwable t){" +
                                "   System.out.println(\"error:\"+t.getLocalizedMessage());" +
                                "   throw t;" +
                                "}finally{" +
                                "   long end = System.currentTimeMillis();" +
                                "   System.out.println(\"cost: \"+(end - begin));" +
                                "}" +
                                "return  result;" +
                                "}");


                        //输出新的字节码到指定文件夹
                        cClass.writeFile("build/generate-class");
                        return cClass.toBytecode();
                    } catch (Throwable e) {
                        e.printStackTrace(System.out);
                    }
                }
                return byteCode;
            }
        });
    }

}
