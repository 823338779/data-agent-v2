package com.datagent.agentv2.conversation.tool.web.fetch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class PublicWebAddressPolicyTest {

    @Test
    void allowsPublicHttpAddress() throws Exception {
        PublicWebAddressPolicy policy =
                new PublicWebAddressPolicy(
                        ignored -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});

        assertThatCode(() -> policy.validate(URI.create("https://example.com/article")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeSchemesCredentialsAndPrivateAddresses() throws Exception {
        PublicWebAddressPolicy privatePolicy =
                new PublicWebAddressPolicy(
                        ignored -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});

        assertThatThrownBy(() -> privatePolicy.validate(URI.create("http://example.com")))
                .isInstanceOf(WebFetchException.class)
                .satisfies(
                        failure ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ((WebFetchException) failure).kind())
                                        .isEqualTo(WebFetchException.Kind.BLOCKED));
        assertThatThrownBy(() -> privatePolicy.validate(URI.create("file:///etc/passwd")))
                .isInstanceOf(WebFetchException.class);
        assertThatThrownBy(
                        () -> privatePolicy.validate(URI.create("https://user:pass@example.com/")))
                .isInstanceOf(WebFetchException.class);
    }

    @Test
    void rejectsCloudMetadataAndCarrierGradeNatRanges() throws Exception {
        for (String address : new String[] {"169.254.169.254", "100.64.0.1"}) {
            PublicWebAddressPolicy policy =
                    new PublicWebAddressPolicy(
                            ignored -> new InetAddress[] {InetAddress.getByName(address)});
            assertThatThrownBy(() -> policy.validate(URI.create("https://example.com")))
                    .isInstanceOf(WebFetchException.class);
        }
    }
}
