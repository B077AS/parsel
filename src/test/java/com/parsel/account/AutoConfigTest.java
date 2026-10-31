package com.parsel.account;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoConfigTest {

    private static final String SAMPLE = """
            <?xml version="1.0"?>
            <clientConfig version="1.1">
              <emailProvider id="example.com">
                <incomingServer type="imap">
                  <hostname>imap.example.com</hostname>
                  <port>993</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                  <authentication>oauth2</authentication>
                  <authentication>password-cleartext</authentication>
                </incomingServer>
                <incomingServer type="pop3">
                  <hostname>pop.example.com</hostname><port>995</port><socketType>SSL</socketType><username>%EMAILADDRESS%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.example.com</hostname>
                  <port>587</port>
                  <socketType>STARTTLS</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
              </emailProvider>
            </clientConfig>
            """;

    @Test
    void readsTheImapAndSmtpServersFromAMozillaFile() {
        AutoConfig.Detected d = AutoConfig.parse(SAMPLE, "database", "will@example.com", "will").orElseThrow();
        assertEquals("imap.example.com", d.imapHost());
        assertEquals(993, d.imapPort());
        assertEquals(Security.SSL, d.imapSecurity());
        assertEquals("smtp.example.com", d.smtpHost());
        assertEquals(587, d.smtpPort());
        assertEquals(Security.STARTTLS, d.smtpSecurity());
        assertEquals("will@example.com", d.username());
        assertEquals("database", d.source());
    }

    @Test
    void theLocalPartPlaceholderBecomesTheUsername() {
        String xml = SAMPLE.replace("<username>%EMAILADDRESS%</username>", "<username>%EMAILLOCALPART%</username>");
        assertEquals("will", AutoConfig.parse(xml, "domain", "will@example.com", "will").orElseThrow().username());
    }

    @Test
    void unencryptedServersAreNotAcceptedFromAFile() {
        String xml = SAMPLE.replace("<socketType>SSL</socketType>", "<socketType>plain</socketType>");
        assertEquals(Optional.empty(), AutoConfig.parse(xml, "domain", "will@example.com", "will"));
    }

    @Test
    void aFileWithADoctypeIsRejectedSoItCannotReadLocalFiles() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + SAMPLE.substring(SAMPLE.indexOf("<clientConfig"));
        assertEquals(Optional.empty(), AutoConfig.parse(xxe, "domain", "will@example.com", "will"));
    }

    @Test
    void garbageIsNotAnAnswer() {
        assertEquals(Optional.empty(), AutoConfig.parse("this is not xml", "domain", "a@example.com", "a"));
        assertEquals(Optional.empty(), AutoConfig.parse("<clientConfig/>", "domain", "a@example.com", "a"));
    }

    @Test
    void theDomainsOwnFileBeatsTheDatabaseAndOnlyTheDomainIsAsked() {
        List<String> asked = new ArrayList<>();
        AutoConfig config = new AutoConfig(url -> {
            asked.add(url);
            return url.contains("autoconfig.example.com") ? Optional.of(SAMPLE) : Optional.of(SAMPLE.replace("imap.example.com", "imap.database.example"));
        }, host -> false);
        AutoConfig.Detected d = config.detect("Will@Example.com");
        assertEquals("domain", d.source());
        assertEquals("imap.example.com", d.imapHost());
        assertEquals(1, asked.size(), "stops at the first answer");
        assertTrue(asked.get(0).startsWith("https://autoconfig.example.com/"));
    }

    @Test
    void theDatabaseIsUsedWhenTheDomainHasNoFile() {
        Map<String, String> files = Map.of("https://autoconfig.thunderbird.net/v1.1/example.com", SAMPLE);
        AutoConfig config = new AutoConfig(url -> Optional.ofNullable(files.get(url)), host -> false);
        assertEquals("database", config.detect("will@example.com").source());
    }

    @Test
    void withNoFileTheServerNamesAreGuessedFromWhatResolves() {
        Set<String> existing = Set.of("mail.example.org", "smtp.example.org");
        AutoConfig config = new AutoConfig(url -> Optional.empty(), existing::contains);
        AutoConfig.Detected d = config.detect("will@example.org");
        assertEquals("guess", d.source());
        assertEquals("mail.example.org", d.imapHost());
        assertEquals("smtp.example.org", d.smtpHost());
        assertEquals(993, d.imapPort());
        assertEquals(Security.SSL, d.imapSecurity());
        assertEquals("will@example.org", d.username());
    }

    @Test
    void whenNothingResolvesTheDefaultNamesAreStillOffered() {
        AutoConfig config = new AutoConfig(url -> Optional.empty(), host -> false);
        assertEquals("imap.example.net", config.detect("a@example.net").imapHost());
    }

    @Test
    void anythingThatIsNotAnAddressIsRefusedBeforeAnyRequest() {
        AutoConfig config = new AutoConfig(url -> { throw new AssertionError("no request expected for " + url); }, host -> false);
        for (String bad : new String[]{"", "nobody", "@example.com", "will@", "will@exa mple.com", "will@x/../y", null}) {
            assertThrows(IllegalArgumentException.class, () -> config.detect(bad), String.valueOf(bad));
        }
    }
}
