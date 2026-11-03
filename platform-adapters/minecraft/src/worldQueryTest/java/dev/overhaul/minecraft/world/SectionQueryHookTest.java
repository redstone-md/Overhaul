package dev.overhaul.minecraft.world;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Checks all epoch/query hook descriptors against each loader's resolved class without transforming it. */
class SectionQueryHookTest {
    @Test void storageHasTheExactShadowFieldsAndMutationTargets() throws Exception {
        var methods=new HashSet<String>();var fields=new HashSet<String>();
        try(var input=getClass().getClassLoader().getResourceAsStream("net/minecraft/world/level/entity/EntitySectionStorage.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    methods.add(name+descriptor);return null;
                }
                @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) {
                    if((access&Opcodes.ACC_FINAL)!=0)fields.add(name+descriptor);return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertTrue(methods.containsAll(Set.of(
                "<init>(Ljava/lang/Class;Lit/unimi/dsi/fastutil/longs/Long2ObjectFunction;)V",
                "createSection(J)Lnet/minecraft/world/level/entity/EntitySection;","remove(J)V",
                "forEachAccessibleNonEmptySection(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V")));
        assertTrue(fields.containsAll(Set.of("sectionsLit/unimi/dsi/fastutil/longs/Long2ObjectMap;",
                "sectionIdsLit/unimi/dsi/fastutil/longs/LongSortedSet;")));
    }
}
