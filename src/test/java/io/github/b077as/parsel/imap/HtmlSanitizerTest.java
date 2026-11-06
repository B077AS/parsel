package io.github.b077as.parsel.imap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    @Test
    void theLineBreaksOfAPlainTextMailSurvive() {
        String cleaned = HtmlSanitizer.clean("<div style=\"white-space:pre-wrap\">Hi Will,\n\nSee you at 8.\nElena</div>");
        assertTrue(cleaned.contains("Hi Will,\n\nSee you at 8.\nElena"), cleaned);
    }

    @Test
    void scriptsAndEventHandlersDoNotSurvive() {
        String cleaned = HtmlSanitizer.clean("<p onclick=\"steal()\">hi</p><script>alert(1)</script><img src=\"x.png\" onerror=\"steal()\">");
        assertFalse(cleaned.contains("script"));
        assertFalse(cleaned.contains("alert"));
        assertFalse(cleaned.toLowerCase().contains("onclick"));
        assertFalse(cleaned.toLowerCase().contains("onerror"));
        assertTrue(cleaned.contains("hi"));
    }

    @Test
    void framesFormsObjectsAndMetaRefreshAreRemoved() {
        String cleaned = HtmlSanitizer.clean(
                "<iframe src=\"https://evil.example\"></iframe><form action=\"https://evil.example\"><input name=\"x\"></form>"
                        + "<object data=\"x\"></object><embed src=\"x\"><meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\"><base href=\"https://evil.example\">");
        for (String tag : new String[]{"iframe", "<form", "<input", "object", "embed", "meta", "base"}) {
            assertFalse(cleaned.toLowerCase().contains(tag), tag + " survived: " + cleaned);
        }
    }

    @Test
    void javascriptLinksAreRemovedAndSafeLinksGetARel() {
        String cleaned = HtmlSanitizer.clean("<a href=\"javascript:alert(1)\">bad</a> <a href=\"https://example.com\">good</a> <a href=\"mailto:a@example.com\">mail</a>");
        assertFalse(cleaned.contains("javascript:"));
        assertTrue(cleaned.contains("href=\"https://example.com\""));
        assertTrue(cleaned.contains("noopener"));
        assertTrue(cleaned.contains("mailto:a@example.com"));
    }

    @Test
    void layoutThatMailRelyOnIsKept() {
        String cleaned = HtmlSanitizer.clean("<table width=\"600\" bgcolor=\"#fff\" cellpadding=\"0\"><tr><td style=\"color:#333;padding:8px\" align=\"center\">x</td></tr></table><center>c</center><font color=\"red\">f</font>");
        assertTrue(cleaned.contains("<table"));
        assertTrue(cleaned.contains("style=\"color:#333;padding:8px\""));
        assertTrue(cleaned.contains("bgcolor"));
        assertTrue(cleaned.contains("<center>"));
        assertTrue(cleaned.contains("<font"));
    }

    @Test
    void dataImagesAreKeptBecauseTheyNeedNoNetwork() {
        String cleaned = HtmlSanitizer.clean("<img src=\"data:image/png;base64,AAAA\">");
        assertTrue(cleaned.contains("data:image/png;base64,AAAA"));
        assertFalse(HtmlSanitizer.hasRemoteContent(cleaned));
    }

    @Test
    void remoteContentIsDetectedInImagesAndStyles() {
        assertTrue(HtmlSanitizer.hasRemoteContent(HtmlSanitizer.clean("<img src=\"https://tracker.example/p.gif\">")));
        // a protocol-relative URL is not on the allowed list, so the sanitizer drops the source: nothing is left to load
        assertFalse(HtmlSanitizer.clean("<img src=\"//tracker.example/p.gif\">").contains("tracker.example"));
        assertTrue(HtmlSanitizer.hasRemoteContent(HtmlSanitizer.clean("<div style=\"background:url(https://tracker.example/b.png)\">x</div>")));
        assertFalse(HtmlSanitizer.hasRemoteContent(HtmlSanitizer.clean("<p>just text <a href=\"https://example.com\">a link</a></p>")));
    }
}
