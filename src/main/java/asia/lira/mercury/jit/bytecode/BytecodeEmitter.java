package asia.lira.mercury.jit.bytecode;

import org.objectweb.asm.Type;

public interface BytecodeEmitter {
    GeneratedClass beginClass(String internalName, String superInternalName);

    interface GeneratedClass {
        GeneratedMethod beginMethod(int access, String name, Type returnType, Type... argumentTypes);

        byte[] toByteArray();
    }

    interface GeneratedMethod {
        void loadThis();

        void loadArgument(int index);

        void loadInt(int value);

        void getField(String ownerInternalName, String name, Type type);

        void putField(String ownerInternalName, String name, Type type);

        void invokeStatic(String ownerInternalName, String name, Type returnType, Type... argumentTypes);

        void invokeVirtual(String ownerInternalName, String name, Type returnType, Type... argumentTypes);

        void returnInt();

        void returnVoid();

        void endMethod();
    }
}
