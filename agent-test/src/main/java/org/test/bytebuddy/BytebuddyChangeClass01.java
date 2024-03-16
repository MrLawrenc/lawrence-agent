package org.test.bytebuddy;

import javassist.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.test.ClassSourceFile;

import java.io.File;
import java.lang.reflect.Method;

public class BytebuddyChangeClass01 {
    public static void main(String[] args) throws Exception {
        DynamicType.Unloaded<ClassSourceFile> a = new ByteBuddy()
                .subclass(ClassSourceFile.class)
                .name("org.test.ClassSourceFile_ByteBuddy")
                .defineField("name", String.class, Visibility.PRIVATE)
                .defineMethod("getName", String.class, Visibility.PUBLIC)
                .intercept(FieldAccessor.ofField("name"))
                .defineMethod("setName", void.class, Visibility.PUBLIC)
                .withParameter(String.class, "name")
                .intercept(FieldAccessor.ofField("name"))

                .defineMethod("printName", String.class, Visibility.PUBLIC)
                .withParameter(String.class, "name")
                .intercept(FixedValue.nullValue())
                .visit(Advice.to(Helper.class).on(ElementMatchers.named("printName")))
                .make();
        a.saveIn(new File("build/generate-class"));

        ClassSourceFile classSourceFile = a.load(BytebuddyChangeClass01.class.getClassLoader())
                .getLoaded()
                .getDeclaredConstructor()
                .newInstance();


        // 调用无参构造方法 创建类实例
        Method printName = classSourceFile.getClass().getMethod("printName", String.class);

        System.out.println("printName: " + printName.invoke(classSourceFile, "Lawrence"));
        System.out.println("printName: " + printName.invoke(classSourceFile, "ByteBuddy"));
    }

    public static class Helper {
        @Advice.OnMethodExit
        public static void printName(@Advice.Return(readOnly = false) String result, @Advice.Argument(value = 0) Object name) {
            if (name.equals("Lawrence"))
                result = "match Lawrence";
            else
                result = "match other[" + name + "]";
        }
    }
}
