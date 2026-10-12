package io.github.kormcrete.swingui.agent;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Test helper: compiles a class into a class folder, writes files, and packs a folder into a jar. */
final class PluginJars {

    private PluginJars() {}

    /** Compiles {@code source} (class {@code className}) against the test classpath into {@code outDir}. */
    static void compile(Path outDir, String className, String source) throws IOException {
        Path src = Files.createTempDirectory("plugin-src").resolve(className.replace('.', '/') + ".java");
        write(src, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(src.toFile());
            Boolean ok = compiler.getTask(null, fm, diagnostics,
                    Arrays.asList("-classpath", System.getProperty("java.class.path"), "-d", outDir.toString()),
                    null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) System.err.println(d);
            }
            assertThat(ok).as("compile " + className).isTrue();
        }
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Packs {@code classes} into {@code jarFile} with a manifest carrying {@code attribute: value} (none when null). */
    static Path jar(Path classes, Path jarFile, String attribute, String value) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (attribute != null) m.getMainAttributes().putValue(attribute, value);
        Files.createDirectories(jarFile.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarFile), m);
             Stream<Path> files = Files.walk(classes)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                out.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, out);
                out.closeEntry();
            }
        }
        return jarFile;
    }
}
