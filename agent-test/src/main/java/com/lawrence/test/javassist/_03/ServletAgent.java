package com.lawrence.test.javassist._03;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

//javax.servlet.http.HttpServlet
//jakarta.servlet.http.HttpServlet
public class ServletAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("Execute agent premain method[" + agentArgs + "].............");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] byteCode) {
                if (className.equals("com/lawrence/test/javassist/_02/Javassist02WriteJavaAgent_Test")) {
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
                                "   result = ($r)$0." + newMethodName + "($$);" +
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
