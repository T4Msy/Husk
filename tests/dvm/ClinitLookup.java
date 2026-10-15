// SPDX-License-Identifier: GPL-2.0-or-later
//
// Regression: JNI GetStaticMethodID(cls, "<clinit>", "()V") as libopenjdk's ObjectStreamClass.hasStaticInitializer makes it,
// the lookup in Husk's report from corvus-experimental-v3 ("no static method <clinit> ()V in class [B"). For a class without a
// static initializer (byte[], or a class that declares none) the lookup must fail with a NoSuchMethodError that the native code
// clears and recognises; for one with an initializer it must succeed. Either way the default serialVersionUID depends on the
// answer, so the UIDs say what the lookup did:
//   - byte[]'s UID is fixed by the JDK (-5984413125824719648); getting it at all means the error was raised and cleared;
//   - Plain (no <clinit>) and WithInit (has one) must get the UIDs the host JVM computes for the same classes: a missing
//     <clinit> reported as present, or a present one as missing, changes them.
// Run on Husk with tools/java-test (run()), and on the host JVM (main()), and compare the two lines.
import java.io.ObjectStreamClass;
import java.io.Serializable;

public class ClinitLookup {
    static class Plain implements Serializable {
        int a;
        String b;
        void m() {}
    }

    static class WithInit implements Serializable {
        static final Object LOCK = new Object();
        int a;
        void m() {}
    }

    static long uid(Class<?> c) {
        return ObjectStreamClass.lookup(c).getSerialVersionUID();
    }

    public static String run() {
        StringBuilder sb = new StringBuilder();
        long bytes = uid(byte[].class);
        sb.append(bytes == -5984413125824719648L ? "byte[] ok" : "byte[] WRONG " + bytes);
        sb.append(" plain=").append(uid(Plain.class));
        sb.append(" withinit=").append(uid(WithInit.class));
        sb.append(" ints=").append(uid(int[].class));
        // a method that does not exist is still an error, through reflection as well
        try {
            ClinitLookup.class.getDeclaredMethod("noSuchMethod");
            sb.append(" missing-method FOUND");
        } catch (NoSuchMethodException expected) {
            sb.append(" missing-method ok");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(run());
    }
}
