import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Supplies ProGuard with library classes when the build JDK omits jmods. */
class ExportRuntimeClasses {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Path config = Path.of(args[1]);
        Files.createDirectories(jar.getParent());
        Path modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
        var names = new HashSet<String>();
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(modules)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = modules.relativize(path);
                String name = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                if (!name.endsWith(".class") || name.equals("module-info.class")) continue;
                if (!names.add(name)) throw new IllegalStateException("Duplicate runtime class: " + name);
                var entry = new JarEntry(name);
                entry.setTime(0);
                output.putNextEntry(entry);
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        if (!names.contains("java/lang/Object.class")) {
            throw new IllegalStateException("Runtime export is missing java.base");
        }
        String quotedPath = jar.toAbsolutePath().toString().replace('\\', '/').replace("'", "\\'");
        Files.writeString(config, "-libraryjars '" + quotedPath + "'\n");
        System.out.println("Exported " + names.size() + " JDK library classes for ProGuard");
    }
}
