package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MemorySettingsTest extends MemoryLifecycleSupport {
    @Test
    void disablingLearningDoesNotDisableExplicitMemoryAndInitializationKeepsItDisabled() {
        var before = memory.snapshot().state();
        var changed =
                memory.updateSettings(
                        "settings-1",
                        new MemorySettingsChange(before.settingsVersion(), null, null, false));
        assertThat(changed.autoLearningEnabled()).isFalse();
        assertThat(changed.readEnabled()).isTrue();
        assertThat(changed.explicitWriteEnabled()).isTrue();
        assertThat(changed.learningEpoch()).isEqualTo(2);
        assertThat(changed.settingsVersion()).isEqualTo(1);
        assertThat(changed.revision()).isEqualTo(1);
        assertThat(changed.learningEnabledAt()).isNull();
        memory.initialize();
        assertThat(memory.snapshot().state()).isEqualTo(changed);
        assertThat(memory.execute(ui("add"), add("中文")).successful()).isTrue();
        var enabled =
                memory.updateSettings("settings-2", new MemorySettingsChange(1, null, null, true));
        assertThat(enabled.learningEpoch()).isEqualTo(3);
        assertThat(enabled.learningEnabledAt()).isAfter(before.learningEnabledAt());
    }

    @Test
    void historyReplayReturnsOriginalStateWithoutChangingCurrentSettings() {
        var change = new MemorySettingsChange(0, null, false, false);
        var first = memory.updateSettings("one", change);
        var second = memory.updateSettings("two", new MemorySettingsChange(1, false, true, true));
        assertThat(memory.updateSettings("one", change)).isEqualTo(first);
        assertThat(memory.snapshot().state()).isEqualTo(second);
        assertThatThrownBy(
                        () ->
                                memory.updateSettings(
                                        "one", new MemorySettingsChange(0, true, false, false)))
                .isInstanceOfSatisfying(
                        MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(MemoryStatus.CONFLICT));
        assertThatThrownBy(
                        () ->
                                memory.updateSettings(
                                        "stale", new MemorySettingsChange(0, null, null, false)))
                .isInstanceOfSatisfying(
                        MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(MemoryStatus.CONFLICT));
    }

    @Test
    void settingsFailureRollsBackSwitchesEpochAndOperation() {
        var before = memory.snapshot().state();
        jdbc.execute(
                "create function reject_settings_operation() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'");
        jdbc.execute(
                "create trigger reject_settings before insert on personal_memory_operation for each row execute function reject_settings_operation()");
        try {
            assertThatThrownBy(
                            () ->
                                    memory.updateSettings(
                                            "fail", new MemorySettingsChange(0, null, null, false)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(memory.snapshot().state()).isEqualTo(before);
            assertThat(operations.find("fail")).isNull();
        } finally {
            jdbc.execute("drop trigger reject_settings on personal_memory_operation");
            jdbc.execute("drop function reject_settings_operation()");
        }
    }

    @Test
    void unchangedSettingsDoNotAdvanceAnyVersionAndKeysCannotBeReusedByCommands() {
        var before = memory.snapshot().state();
        assertThat(memory.updateSettings("noop", new MemorySettingsChange(0, true, null, true)))
                .isEqualTo(before);
        assertThat(memory.execute(ui("noop"), add("中文")).status()).isEqualTo(MemoryStatus.CONFLICT);
        memory.execute(ui("command"), add("中文"));
        assertThatThrownBy(
                        () ->
                                memory.updateSettings(
                                        "command", new MemorySettingsChange(0, null, null, false)))
                .isInstanceOfSatisfying(
                        MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(MemoryStatus.CONFLICT));
    }
}
