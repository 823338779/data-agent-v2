package com.datagent.agentv2.git.local;

import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.GitException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;

/** 以参数列表执行固定 Git，可控地限制时间和输出，并隔离交互、签名、hook 与外部协议。 */
@RequiredArgsConstructor
public final class GitCommandRunner {
    private final GitProperties properties;

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
                        "protocol.allow=never",
                        "-c",
                        "protocol.ext.allow=never",
                        "-c",
                        "protocol.file.allow=always",
                        "-c",
                        "protocol.http.allow=always",
                        "-c",
                        "protocol.https.allow=always",
                        "-c",
                        "protocol.ssh.allow=always"));
        command.addAll(arguments);
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        removeGitOverrides(environment);
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", isWindows() ? "NUL" : "/dev/null");
        environment.put("GIT_ASKPASS", isWindows() ? "cmd /c exit 1" : "/bin/false");
        environment.put("GIT_ALLOW_PROTOCOL", "file:http:https:ssh");
        environment.putAll(extraEnvironment);
        Process process = null;
        CompletableFuture<CapturedOutput> output = null;
        try {
            process = builder.start();
            Process runningProcess = process;
            output =
                    CompletableFuture.supplyAsync(
                            () -> readBounded(runningProcess.getInputStream()));
            if (!process.waitFor(properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process, output);
                throw new GitException("GIT_TIMEOUT", "Git 命令执行超时");
            }
            CapturedOutput captured = output.get(5, TimeUnit.SECONDS);
            if (captured.truncated()) {
                throw new GitException("GIT_OUTPUT_TOO_LARGE", "Git 命令输出超过服务端上限");
            }
            return new Result(
                    process.exitValue(),
                    new String(captured.bytes(), StandardCharsets.UTF_8),
                    isValidUtf8(captured.bytes()));
        } catch (GitException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            terminate(process, output);
            Thread.currentThread().interrupt();
            throw new GitException("GIT_INTERRUPTED", "Git 命令被中断", exception);
        } catch (ExecutionException exception) {
            terminate(process, output);
            if (exception.getCause() instanceof GitException gitException) {
                throw gitException;
            }
            throw new GitException("GIT_COMMAND_FAILED", "无法读取 Git 命令结果", exception);
        } catch (Exception exception) {
            terminate(process, output);
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

    static void removeGitOverrides(Map<String, String> environment) {
        environment
                .keySet()
                .removeIf(
                        key ->
                                key.equals("GIT_DIR")
                                        || key.equals("GIT_WORK_TREE")
                                        || key.equals("GIT_INDEX_FILE")
                                        || key.equals("GIT_OBJECT_DIRECTORY")
                                        || key.equals("GIT_ALTERNATE_OBJECT_DIRECTORIES")
                                        || key.equals("GIT_SSH")
                                        || key.equals("GIT_SSH_COMMAND")
                                        || key.equals("GIT_PROXY_COMMAND")
                                        || key.equals("GIT_EXTERNAL_DIFF")
                                        || key.equals("GIT_DIFF_OPTS")
                                        || key.equals("GIT_ALLOW_PROTOCOL")
                                        || key.equals("GIT_PROTOCOL_FROM_USER")
                                        || key.equals("GIT_CONFIG_COUNT")
                                        || key.equals("GIT_CONFIG_PARAMETERS")
                                        || key.startsWith("GIT_CONFIG_KEY_")
                                        || key.startsWith("GIT_CONFIG_VALUE_"));
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException exception) {
            return false;
        }
    }

    /** 中断和超时必须终止整个子进程树，否则延迟执行的 hook/helper 仍可能继续写入。 */
    private static void terminate(Process process, CompletableFuture<CapturedOutput> outputFuture) {
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (outputFuture != null) {
            outputFuture.cancel(true);
        }
    }

    public record Result(int exitCode, String output, boolean validUtf8) {}

    private record CapturedOutput(byte[] bytes, boolean truncated) {}
}
