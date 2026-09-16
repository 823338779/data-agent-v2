package com.datagent.agentv2.memory;

import java.util.List;
import java.util.Objects;

/** 统一写入边界接受的有限命令集合；证据 ID 在构造时冻结，避免调用方事后篡改。 */
public sealed interface MemoryCommand {
    MemoryWriteReason reason();

    List<String> evidenceMessageIds();

    record Add(
            MemoryKind kind,
            String content,
            MemoryWriteReason reason,
            List<String> evidenceMessageIds,
            boolean restore)
            implements MemoryCommand {
        public Add {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(reason, "reason");
            evidenceMessageIds = List.copyOf(evidenceMessageIds);
        }
    }

    record Replace(
            String memoryId,
            long expectedVersion,
            String newContent,
            MemoryWriteReason reason,
            List<String> evidenceMessageIds)
            implements MemoryCommand {
        public Replace {
            Objects.requireNonNull(memoryId, "memoryId");
            Objects.requireNonNull(newContent, "newContent");
            Objects.requireNonNull(reason, "reason");
            evidenceMessageIds = List.copyOf(evidenceMessageIds);
            requireVersion(expectedVersion);
        }
    }

    record Remove(
            String memoryId,
            long expectedVersion,
            MemoryWriteReason reason,
            List<String> evidenceMessageIds)
            implements MemoryCommand {
        public Remove {
            Objects.requireNonNull(memoryId, "memoryId");
            Objects.requireNonNull(reason, "reason");
            evidenceMessageIds = List.copyOf(evidenceMessageIds);
            requireVersion(expectedVersion);
        }
    }

    record Confirm(String memoryId, long expectedVersion, List<String> evidenceMessageIds)
            implements MemoryCommand {
        public Confirm {
            Objects.requireNonNull(memoryId, "memoryId");
            evidenceMessageIds = List.copyOf(evidenceMessageIds);
            requireVersion(expectedVersion);
        }

        @Override
        public MemoryWriteReason reason() {
            return MemoryWriteReason.AGENT_INFERRED;
        }
    }

    private static void requireVersion(long expectedVersion) {
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion 必须为正数");
        }
    }
}
