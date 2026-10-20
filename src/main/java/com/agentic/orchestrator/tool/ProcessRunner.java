package com.agentic.orchestrator.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external process with a timeout. Package-private on purpose: agents get no generic "exec"; only
 * {@link GitTool} and {@link BuildTool}, which each build their own fixed argument lists, may start processes.
 */
final class ProcessRunner {

    record Result(int exitCode, String output, boolean timedOut) {
        boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private ProcessRunner() {
    }

    static Result run(List<String> argv, Path workingDir, Map<String, String> extraEnv, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDir.toFile()).redirectErrorStream(true);
        builder.environment().putAll(extraEnv);
        try {
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), output));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(5_000);
            synchronized (output) {
                return new Result(finished ? process.exitValue() : -1, output.toString(), !finished);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + argv.getFirst(), e);
        }
    }

    private static void drain(InputStream in, StringBuilder sink) {
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (sink) {
                    sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // stream closed when the process is killed
        }
    }

    static String tail(String output, int maxChars) {
        return output.length() <= maxChars ? output : "...[truncated]...\n" + output.substring(output.length() - maxChars);
    }
}
