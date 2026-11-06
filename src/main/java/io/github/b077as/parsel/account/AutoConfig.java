package io.github.b077as.parsel.account;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Works out the server settings for an email address so the user usually only types the address and the password.
 * It asks, in order: the domain's own autoconfig file, Mozilla's public database of providers (the one Thunderbird uses),
 * and as a last resort guesses {@code imap.<domain>} and {@code smtp.<domain>}. Only the domain part of the address is
 * ever sent anywhere, and never the password.
 */
public final class AutoConfig {

    /** @param source where the settings came from: {@code domain}, {@code database} or {@code guess} (a guess is shown to the user as one) */
    public record Detected(String source, String imapHost, int imapPort, Security imapSecurity,
                           String smtpHost, int smtpPort, Security smtpSecurity, String username) {}

    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$");
    private static final int MAX_XML_BYTES = 200_000;

    private final Function<String, Optional<String>> fetcher;
    private final Predicate<String> hostExists;

    /** @param fetcher returns the body of a URL, or empty on any failure; @param hostExists whether a host name resolves */
    public AutoConfig(Function<String, Optional<String>> fetcher, Predicate<String> hostExists) {
        this.fetcher = fetcher;
        this.hostExists = hostExists;
    }

    /** The real thing: HTTPS to the autoconfig servers and DNS lookups. */
    public static AutoConfig online() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build();
        Function<String, Optional<String>> fetcher = url -> {
            try {
                HttpResponse<byte[]> response = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200 || response.body().length > MAX_XML_BYTES) {
                    return Optional.empty();
                }
                return Optional.of(new String(response.body(), StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        };
        Predicate<String> resolves = host -> {
            try {
                InetAddress.getByName(host);
                return true;
            } catch (java.io.IOException e) {
                return false;
            }
        };
        return new AutoConfig(fetcher, resolves);
    }

    /** @throws IllegalArgumentException if {@code email} is not an address */
    public Detected detect(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        if (at < 1 || at == email.length() - 1) {
            throw new IllegalArgumentException("That does not look like an email address");
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
        if (!DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("That does not look like an email address");
        }
        for (String[] candidate : new String[][]{
                {"domain", "https://autoconfig." + domain + "/mail/config-v1.1.xml?emailaddress=" + java.net.URLEncoder.encode(email, StandardCharsets.UTF_8)},
                {"database", "https://autoconfig.thunderbird.net/v1.1/" + domain}}) {
            Optional<Detected> found = fetcher.apply(candidate[1]).flatMap(xml -> parse(xml, candidate[0], email, local));
            if (found.isPresent()) {
                return found.get();
            }
        }
        return guess(domain, email);
    }

    private Detected guess(String domain, String email) {
        String imap = firstExisting(List.of("imap." + domain, "mail." + domain, domain)).orElse("imap." + domain);
        String smtp = firstExisting(List.of("smtp." + domain, "mail." + domain, domain)).orElse("smtp." + domain);
        return new Detected("guess", imap, 993, Security.SSL, smtp, 465, Security.SSL, email);
    }

    private Optional<String> firstExisting(List<String> hosts) {
        return hosts.stream().filter(hostExists).findFirst();
    }

    /** Reads a Mozilla autoconfig XML file. Package-visible for tests. */
    static Optional<Detected> parse(String xml, String source, String email, String local) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no XXE from a file we fetched off the network
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element imap = server(doc.getElementsByTagName("incomingServer"), "imap");
            Element smtp = server(doc.getElementsByTagName("outgoingServer"), "smtp");
            if (imap == null || smtp == null) {
                return Optional.empty();
            }
            Security imapSecurity = security(text(imap, "socketType"));
            Security smtpSecurity = security(text(smtp, "socketType"));
            String imapHost = text(imap, "hostname");
            String smtpHost = text(smtp, "hostname");
            int imapPort = Integer.parseInt(text(imap, "port"));
            int smtpPort = Integer.parseInt(text(smtp, "port"));
            if (imapSecurity == null || smtpSecurity == null || imapHost == null || smtpHost == null) {
                return Optional.empty();
            }
            String username = switch (String.valueOf(text(imap, "username"))) {
                case "%EMAILLOCALPART%" -> local;
                default -> email; // %EMAILADDRESS%, or anything we do not understand: the address is the usual login
            };
            return Optional.of(new Detected(source, imapHost.strip(), imapPort, imapSecurity, smtpHost.strip(), smtpPort, smtpSecurity, username));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Element server(NodeList nodes, String type) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && type.equalsIgnoreCase(element.getAttribute("type"))) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent().strip();
    }

    private static Security security(String socketType) {
        if (socketType == null) {
            return null;
        }
        return switch (socketType.toUpperCase(Locale.ROOT)) {
            case "SSL" -> Security.SSL;
            case "STARTTLS" -> Security.STARTTLS;
            default -> null; // "plain" is not offered: Parsel does not send passwords unencrypted
        };
    }
}
