package com.datagent.agentv2.conversation.tool.web.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 在每次请求和重定向前阻断本机、内网、链路本地、元数据及保留地址。 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class PublicWebAddressPolicy {

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    @Autowired
    public PublicWebAddressPolicy() {
        this(InetAddress::getAllByName);
    }

    /** 校验 URL 结构，并要求域名解析出的每一个地址都属于可公开路由范围。 */
    public ValidatedWebAddress validate(URI uri) throws WebFetchException {
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw blocked();
        }
        try {
            InetAddress[] addresses = resolver.resolve(uri.getHost());
            if (addresses.length == 0) {
                throw new WebFetchException(
                        WebFetchException.Kind.UNAVAILABLE, "Host resolved to no address");
            }
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw blocked();
                }
            }
            return new ValidatedWebAddress(uri, List.of(addresses));
        } catch (UnknownHostException failure) {
            throw new WebFetchException(
                    WebFetchException.Kind.UNAVAILABLE, "Host could not be resolved", failure);
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(address.getAddress()[0]);
            return (first & 0xfe) != 0xfc;
        }
        return false;
    }

    private static WebFetchException blocked() {
        return new WebFetchException(
                WebFetchException.Kind.BLOCKED, "Target address is not publicly routable");
    }
}
