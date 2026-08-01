package com.datagent.agentv2.platform.id;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class PrefixedUuidIdGenerator implements IdGenerator {

    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]*");

    @Override
    public String next(String prefix) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("invalid id prefix");
        }
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
