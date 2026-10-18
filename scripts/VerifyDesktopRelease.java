import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

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
