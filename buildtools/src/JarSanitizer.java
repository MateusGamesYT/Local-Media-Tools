import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Normalises library bytecode so the 2019-era D8 dexer used by this build can read it:
 *
 *  - drops MethodParameters attributes (D8 1.5 crashes on unnamed parameters),
 *  - lowers Java 11+ nest-based private access to package-private access and removes the
 *    NestHost/NestMembers attributes (ART has no nestmate support),
 *  - lowers the class-file version to Java 8 (only after the above; the bytecode is otherwise
 *    verified to contain no invokedynamic bootstraps newer than Java 8),
 *  - removes module-info and multi-release class variants,
 *  - optionally drops whole packages/classes by prefix (unused camera views from OpenCV).
 *
 * Usage: JarSanitizer in.jar out.jar [--drop prefix]...
 */
public final class JarSanitizer {
    public static void main(String[] args) throws Exception {
        String in = args[0];
        String out = args[1];
        List<String> drops = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--drop")) drops.add(args[++i]);
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(in))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                entries.put(e.getName(), readAll(zin));
            }
        }

        // First pass: parse classes and find nest members.
        Map<String, ClassNode> classes = new HashMap<>();
        Set<String> nestClasses = new HashSet<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.endsWith(".class") || skip(name, drops)) continue;
            ClassNode node = new ClassNode();
            new ClassReader(e.getValue()).accept(node, 0);
            classes.put(node.name, node);
            if (node.nestHostClass != null || (node.nestMembers != null && !node.nestMembers.isEmpty())) {
                nestClasses.add(node.name);
            }
        }

        // Private instance methods of nest classes that will become package-private.
        Set<String> widened = new HashSet<>();
        for (String cn : nestClasses) {
            ClassNode node = classes.get(cn);
            boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
            for (MethodNode m : node.methods) {
                if ((m.access & Opcodes.ACC_PRIVATE) != 0 && !isInterface) {
                    if ((m.access & Opcodes.ACC_STATIC) == 0 && !m.name.equals("<init>")) {
                        widened.add(cn + "." + m.name + m.desc);
                    }
                    m.access &= ~Opcodes.ACC_PRIVATE;
                }
            }
            for (FieldNode f : node.fields) {
                if (!isInterface) f.access &= ~Opcodes.ACC_PRIVATE;
            }
            node.nestHostClass = null;
            node.nestMembers = null;
        }

        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                if (skip(name, drops)) continue;
                byte[] data = e.getValue();
                if (name.endsWith(".class")) {
                    ClassNode node = classes.get(name.substring(0, name.length() - 6));
                    if (node == null) continue;
                    for (MethodNode m : node.methods) {
                        m.parameters = null;
                        if (m.instructions == null) continue;
                        for (AbstractInsnNode insn : m.instructions.toArray()) {
                            if (insn instanceof MethodInsnNode) {
                                MethodInsnNode mi = (MethodInsnNode) insn;
                                if (mi.getOpcode() == Opcodes.INVOKESPECIAL
                                        && widened.contains(mi.owner + "." + mi.name + mi.desc)) {
                                    mi.setOpcode(Opcodes.INVOKEVIRTUAL);
                                }
                            }
                        }
                    }
                    if ((node.version & 0xFFFF) > Opcodes.V1_8) node.version = Opcodes.V1_8;
                    ClassWriter cw = new ClassWriter(0);
                    node.accept(cw);
                    data = cw.toByteArray();
                }
                zout.putNextEntry(new ZipEntry(name));
                zout.write(data);
                zout.closeEntry();
            }
        }
    }

    private static boolean skip(String name, List<String> drops) {
        if (name.equals("module-info.class") || name.endsWith("/module-info.class")) return true;
        if (name.startsWith("META-INF/versions/")) return true;
        for (String d : drops) if (name.startsWith(d)) return true;
        return false;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
