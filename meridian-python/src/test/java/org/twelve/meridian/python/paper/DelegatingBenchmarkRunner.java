package org.twelve.meridian.python.paper;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 论文实验工具——反射启动 meridian-python 模块内既有 benchmark 测试，捕获其 stdout
 * 到 {@code target/paper-reports/} 下指定文件。供 RQ2_* paper 测试类复用。
 *
 * <p>设计目标：实验员（论文实验作者）不重写既有 benchmark 的实现逻辑（不动框架代码也不
 * 重复实现），只通过反射 + stdout 捕获把既有 benchmark 的输出**结构化归档**供论文回填。
 *
 * <p>注意：既有 benchmark 测试方法可能是 package-private（如 {@code class Table1BenchmarkTest}），
 * 跨包反射调用需要 {@code setAccessible(true)}。
 */
final class DelegatingBenchmarkRunner {

    private DelegatingBenchmarkRunner() {}

    /** 反射启动 fqcn 类的 @Test 方法 testMethodName，捕获 stdout，写入 outputLog。 */
    static CaptureResult runAndCapture(String fqcn, String testMethodName, Path outputLog) throws Exception {
        Files.createDirectories(outputLog.getParent());

        Class<?> testClass = Class.forName(fqcn);
        Constructor<?> ctor = testClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();

        Method m = testClass.getDeclaredMethod(testMethodName);
        m.setAccessible(true);

        // 捕获 stdout + stderr
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(new TeeOutputStream(captured, originalOut), true, StandardCharsets.UTF_8);
        System.setOut(tee);
        System.setErr(tee);

        long t0 = System.currentTimeMillis();
        Throwable failure = null;
        try {
            m.invoke(instance);
        } catch (InvocationTargetException ite) {
            failure = ite.getCause() != null ? ite.getCause() : ite;
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        long elapsedMs = System.currentTimeMillis() - t0;

        String stdoutText = captured.toString(StandardCharsets.UTF_8);
        Files.writeString(outputLog, stdoutText);

        return new CaptureResult(stdoutText, elapsedMs, failure);
    }

    /** 单次反射启动 + 捕获的结果。 */
    record CaptureResult(String capturedStdout, long elapsedMs, Throwable failure) {
        boolean passed() { return failure == null; }
    }

    // ── 通用表格解析 ─────────────────────────────────────────────────────────

    /**
     * 把 stdout 中的"行：数字 数字 ... 数字x"这种条目提取为 CSV——给后续手工 / 论文回填一个
     * 机读起点。这一解析故意保守（只识别明显数值行），不试图理解每列的语义；
     * 语义对应由具体 RQ2_* paper 类负责。
     */
    static void extractNumericLines(String stdout, Path csvOut) throws IOException {
        Files.createDirectories(csvOut.getParent());
        Pattern numericLine = Pattern.compile("[║│|]?\\s*([A-Za-z_]\\w*(?:\\(\\d+\\))?)\\s*[║│|]?[\\s\\d.x×]+");
        StringBuilder sb = new StringBuilder("raw_line\n");
        for (String line : stdout.split("\n")) {
            Matcher m = numericLine.matcher(line);
            if (m.find() && line.matches(".*\\d+\\.\\d+.*")) {
                sb.append("\"").append(line.trim().replace("\"", "\"\"")).append("\"\n");
            }
        }
        Files.writeString(csvOut, sb.toString());
    }

    /** 简单 tee：同时写入 captured 与 console。 */
    private static class TeeOutputStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeOutputStream(OutputStream a, OutputStream b) { this.a = a; this.b = b; }

        @Override public void write(int x) throws IOException { a.write(x); b.write(x); }
        @Override public void write(byte[] buf, int off, int len) throws IOException { a.write(buf, off, len); b.write(buf, off, len); }
        @Override public void flush() throws IOException { a.flush(); b.flush(); }
    }
}
