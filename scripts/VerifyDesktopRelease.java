import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Runs against processed release jars, without unprocessed test dependencies. */
public final class VerifyDesktopRelease {
    private static final Map<String, String> classes = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the release ProGuard mapping path");
        }
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (line.startsWith("okio.") && line.endsWith(":")) {
                String[] names = line.substring(0, line.length() - 1).split(" -> ", 2);
                if (names.length == 2) classes.put(names[0], names[1]);
            }
        }
        if (classes.isEmpty()) throw new AssertionError("Release mapping contains no Okio classes");

        // Loading without initialization is insufficient: requesting members
        // forces JVM verification even for methods no startup screen calls.
        var verified = new LinkedHashSet<>(classes.values());
        for (String name : verified) {
            Class<?> type = Class.forName(name, false, VerifyDesktopRelease.class.getClassLoader());
            type.getDeclaredConstructors();
            type.getDeclaredMethods();
        }

        Class<?> okio = releaseClass("okio.Okio");
        Class<?> source = releaseClass("okio.Source");
        Class<?> bufferedSource = releaseClass("okio.BufferedSource");
        Method wrap = uniqueMethod(okio, true, source, java.io.InputStream.class);
        Method buffer = uniqueMethod(okio, true, bufferedSource, source);
        Method read = uniqueMethod(bufferedSource, false, byte[].class);
        byte[] expected = "DAView release stream \u6d4b\u8bd5".getBytes(StandardCharsets.UTF_8);

        // Exercise the facade and multifile implementation that used to fail
        // with Bad return type when HTTP/image loading first buffered a stream.
        for (boolean alreadyBuffered : new boolean[] {false, true}) {
            Object raw = wrap.invoke(null, new ByteArrayInputStream(expected));
            if (alreadyBuffered) raw = buffer.invoke(null, raw);
            Object input = buffer.invoke(null, raw);
            try {
                byte[] actual = (byte[]) read.invoke(input);
                if (!Arrays.equals(expected, actual)) throw new AssertionError("Buffered stream data differs");
            } finally {
                ((java.io.Closeable) input).close();
            }
        }
        System.out.println("Verified " + verified.size() + " release Okio classes and both buffered stream reads");

        verifyAllClasses();
        verifySqlite();
    }

    /**
     * Every class of the app is linked and verified, not only the ones a
     * startup screen happens to load, and every enum must still answer
     * Enum.valueOf. That call finds the constants by invoking a public static
     * method named "values" through reflection; obfuscation once renamed it,
     * and each library then read as OTHER and each item as MOVIE.
     *
     * Library classes that cannot load here refer to optional dependencies
     * the rules mark -dontwarn; they are counted, not failed.
     */
    private static void verifyAllClasses() throws Exception {
        ClassLoader loader = VerifyDesktopRelease.class.getClassLoader();
        List<String> failures = new ArrayList<>();
        int own = 0;
        int enums = 0;
        int skipped = 0;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(entry)) {
                for (JarEntry file : Collections.list(jar.entries())) {
                    String path = file.getName();
                    if (!path.endsWith(".class") || path.startsWith("META-INF/")
                            || path.endsWith("module-info.class") || path.endsWith("package-info.class")) continue;
                    String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
                    boolean ours = name.startsWith("com.daview.");
                    Class<?> type;
                    try {
                        type = Class.forName(name, false, loader);
                        if (ours) {
                            type.getDeclaredConstructors();
                            type.getDeclaredMethods();
                            type.getDeclaredFields();
                            own++;
                        }
                    } catch (LinkageError | ClassNotFoundException | SecurityException error) {
                        if (ours) failures.add(name + ": " + error);
                        else skipped++;
                        continue;
                    }
                    if (!type.isEnum()) continue;
                    enums++;
                    try {
                        Method values = type.getMethod("values");
                        Method valueOf = type.getMethod("valueOf", String.class);
                        if (!Modifier.isStatic(values.getModifiers()) || values.getReturnType() != type.arrayType()
                                || !Modifier.isStatic(valueOf.getModifiers()) || valueOf.getReturnType() != type) {
                            failures.add(name + ": values()/valueOf(String) do not have their enum signatures");
                        } else if (ours) {
                            Object[] constants = type.getEnumConstants();
                            if (constants == null) {
                                failures.add(name + ": Enum.valueOf cannot see its constants");
                            } else {
                                for (Object constant : constants) {
                                    String constantName = ((Enum<?>) constant).name();
                                    // The call each generated valueOf makes, and the
                                    // one that failed; it also reaches non-public enums.
                                    try {
                                        if (enumValueOf(type, constantName) != constant) {
                                            failures.add(name + ": valueOf(\"" + constantName + "\") returned another constant");
                                        }
                                    } catch (IllegalArgumentException thrown) {
                                        failures.add(name + ": Enum.valueOf(\"" + constantName + "\") threw " + thrown.getMessage());
                                    }
                                }
                            }
                        }
                    } catch (NoSuchMethodException missing) {
                        failures.add(name + ": enum without public values()/valueOf(String); Enum.valueOf would throw");
                    } catch (LinkageError error) {
                        if (ours) failures.add(name + ": " + error);
                        else skipped++;
                    }
                }
            }
        }
        if (own == 0) throw new AssertionError("No com.daview classes on the release classpath");
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " release class failure(s):%n  ".formatted()
                + String.join("%n  ".formatted(), failures));
        }
        System.out.println("Verified " + own + " app classes and " + enums + " enums (" + skipped
            + " library classes with optional dependencies skipped)");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValueOf(Class<?> type, String constantName) {
        return Enum.valueOf((Class) type, constantName);
    }

    /** The driver is registered by name and loads its native library from a resource beside its classes. */
    private static void verifySqlite() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             ResultSet result = connection.createStatement().executeQuery("SELECT sqlite_version()")) {
            if (!result.next() || result.getString(1).isBlank()) throw new AssertionError("SQLite returned no version");
            System.out.println("Opened SQLite " + result.getString(1) + " through the release driver");
        }
    }

    private static Class<?> releaseClass(String original) throws ClassNotFoundException {
        String name = classes.get(original);
        if (name == null) throw new AssertionError("Missing release class: " + original);
        return Class.forName(name);
    }

    // Resolve by types so a changed obfuscation dictionary cannot make this
    // check accidentally run against a hard-coded class or method name.
    private static Method uniqueMethod(Class<?> owner, boolean isStatic, Class<?> result, Class<?>... parameters) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) == isStatic
                    && method.getReturnType() == result
                    && Arrays.equals(method.getParameterTypes(), parameters)) {
                if (found != null) throw new AssertionError("Ambiguous release method on " + owner.getName());
                found = method;
            }
        }
        if (found == null) throw new AssertionError("Missing release method on " + owner.getName());
        found.setAccessible(true);
        return found;
    }
}
