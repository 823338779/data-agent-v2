package com.datagent.agentv2.git.local;

import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.GitException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** 以参数列表执行固定 Git，可控地限制时间和输出，并隔离交互、签名、hook 与外部协议。 */
public final class GitCommandRunner {
    private final GitProperties properties;

    public GitCommandRunner(GitProperties properties) {
        this.properties = properties;
    }

    public Result run(Path directory, List<String> arguments) {
        Result result = runAllowFailure(directory, arguments, Map.of());
        if (result.exitCode() != 0) {
            throw new GitException(
                    "GIT_COMMAND_FAILED",
                    "Git 命令失败（exit=" + result.exitCode() + "): " + sanitize(result.output()));
        }
        return result;
    }

    Result run(Path directory, List<String> arguments, Map<String, String> extraEnvironment) {
        Result result = runAllowFailure(directory, arguments, extraEnvironment);
        if (result.exitCode() != 0) {
            throw new GitException(
                    "GIT_COMMAND_FAILED",
                    "Git 命令失败（exit=" + result.exitCode() + "): " + sanitize(result.output()));
        }
        return result;
    }

    Result runAllowFailure(
            Path directory, List<String> arguments, Map<String, String> extraEnvironment) {
        List<String> command = new ArrayList<>();
        command.add(properties.executable());
        command.addAll(
                List.of(
                        "-c",
                        "core.hooksPath=NUL",
                        "-c",
                        "commit.gpgSign=false",
                        "-c",
                        "tag.gpgSign=false",
                        "-c",
                        "core.autocrlf=false",
                        "-c",
                        "protocol.ext.allow=never",
                        "-c",
                        "protocol.file.allow=always"));
        command.addAll(arguments);
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", isWindows() ? "NUL" : "/dev/null");
        environment.put("GIT_ASKPASS", isWindows() ? "cmd /c exit 1" : "/bin/false");
        environment.putAll(extraEnvironment);
        try {
            Process process = builder.start();
            CompletableFuture<CapturedOutput> output =
                    CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()));
            if (!process.waitFor(properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitException("GIT_TIMEOUT", "Git 命令执行超时");
            }
            CapturedOutput captured = output.get(5, TimeUnit.SECONDS);
            if (captured.truncated()) {
                throw new GitException("GIT_OUTPUT_TOO_LARGE", "Git 命令输出超过服务端上限");
            }
            return new Result(
                    process.exitValue(), new String(captured.bytes(), StandardCharsets.UTF_8));
        } catch (GitException exception) {
            throw exception;
        } catch (Exception exception) {
            Thread.currentThread().interrupt();
            throw new GitException("GIT_COMMAND_FAILED", "无法执行 Git 命令", exception);
        }
    }

    private CapturedOutput readBounded(InputStream stream) {
        try (stream;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            boolean truncated = false;
            while ((read = stream.read(buffer)) >= 0) {
                int remaining = properties.maxCommandOutputBytes() - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
                truncated |= read > remaining;
            }
            return new CapturedOutput(output.toByteArray(), truncated);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String sanitize(String output) {
        String sanitized = output.replace(properties.remoteUri(), "<remote>");
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    public record Result(int exitCode, String output) {}

    private record CapturedOutput(byte[] bytes, boolean truncated) {}
}
