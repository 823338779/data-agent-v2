package com.datagent.agentv2.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;

class ClasspathSkillResourceSourceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesResourceOnSuccessAndReadFailure(boolean failRead) throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        var input =
                new ByteArrayInputStream("test".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override
                    public byte[] readAllBytes() {
                        if (failRead) {
                            throw new java.io.UncheckedIOException(new IOException("read failure"));
                        }
                        return super.readAllBytes();
                    }

                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
        Resource resource = mock(Resource.class);
        when(resource.getURI()).thenReturn(URI.create("file:/skills/test/SKILL.md"));
        when(resource.getInputStream()).thenReturn(input);
        try (var ignored =
                mockConstruction(
                        PathMatchingResourcePatternResolver.class,
                        (resolver, context) ->
                                when(resolver.getResources(anyString()))
                                        .thenReturn(new Resource[] {resource}))) {
            var source = new ClasspathSkillResourceSource(new MockEnvironment());
            if (failRead) {
                assertThatThrownBy(source::load).isInstanceOf(java.io.UncheckedIOException.class);
            } else {
                assertThat(source.load()).containsKey("skills/test/SKILL.md");
            }
            assertThat(closed).isTrue();
        }
    }
}
