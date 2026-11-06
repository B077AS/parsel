package io.github.b077as.parsel.send;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutgoingHtmlTest {

    @Test
    void keepsWhatTheComposerMakes() {
        String html = "<h2>Plan</h2><p style=\"text-align: center\">Hi <strong>there</strong>, <em>you</em> <u>all</u> <s>no</s></p>"
                + "<ul><li><p>one</p></li><li><p>two</p></li></ul><ol><li><p>first</p></li></ol><pre><code>x = 1</code></pre><hr>";
        String clean = OutgoingHtml.clean(html);
        assertTrue(clean.contains("<h2>Plan</h2>"), clean);
        assertTrue(clean.contains("<p style=\"text-align:center\">"), clean);
        assertTrue(clean.contains("<strong>there</strong>") && clean.contains("<em>you</em>") && clean.contains("<u>all</u>") && clean.contains("<s>no</s>"), clean);
        assertTrue(clean.contains("<ul>") && clean.contains("<ol>") && clean.contains("<hr>"), clean);
    }

    @Test
    void dropsAnythingThatRunsLoadsOrSubmits() {
        String clean = OutgoingHtml.clean("<p onclick=\"steal()\">Hi</p><script>alert(1)</script><img src=\"http://t.example/p.gif\">"
                + "<iframe src=\"http://x.example\"></iframe><form action=\"http://x.example\"><input name=a></form><style>p{color:red}</style>");
        assertEquals("<p>Hi</p>", clean);
    }

    @Test
    void onlyHttpHttpsAndMailtoLinksSurviveAndOnlyAsPlainLinks() {
        String clean = OutgoingHtml.clean("<a href=\"https://example.com\" target=\"_blank\" onclick=\"x()\">ok</a> "
                + "<a href=\"javascript:alert(1)\">bad</a> <a href=\"data:text/html,x\">worse</a> <a href=\"mailto:a@b.co\">mail</a>");
        assertTrue(clean.contains("<a href=\"https://example.com\">ok</a>"), clean);
        assertTrue(clean.contains("<a href=\"mailto:a@b.co\">mail</a>"), clean);
        assertFalse(clean.contains("javascript"), clean);
        assertFalse(clean.contains("data:"), clean);
        assertTrue(clean.contains("bad") && clean.contains("worse"), "the text of a refused link stays: " + clean);
    }

    @Test
    void aHighlightBecomesASpanWithABackground() {
        String clean = OutgoingHtml.clean("<mark data-color=\"#fff3a3\" style=\"background-color: #fff3a3; color: inherit\">hit</mark>");
        assertTrue(clean.startsWith("<span style=\"background-color:#fff3a3;color:inherit\">hit</span>"), clean);
        assertFalse(clean.contains("data-color"), clean);
    }

    @Test
    void styleKeepsOnlyColoursFontFamilyAndAlignment() {
        assertEquals("color:#c4332b;font-family:Georgia, serif",
                OutgoingHtml.filterStyle("color: #c4332b; position: fixed; font-family: Georgia, serif; background: url(http://t.example/x)"));
        assertEquals("", OutgoingHtml.filterStyle("color: expression(alert(1)); font-family: a}b{c; text-align: sideways"));
        assertEquals("text-align:right", OutgoingHtml.filterStyle("text-align: right"));
        assertEquals("font-family:Consolas, 'Courier New', monospace", OutgoingHtml.filterStyle("font-family: Consolas, 'Courier New', monospace"));
    }

    @Test
    void quotesAndCodeLookTheSameInEveryMailClient() {
        String clean = OutgoingHtml.clean("<blockquote><p>old</p></blockquote><pre>a</pre>");
        assertTrue(clean.contains("<blockquote style=\"") && clean.contains("border-left"), clean);
        assertTrue(clean.contains("<pre style=\""), clean);
    }

    @Test
    void plainTextKeepsParagraphsListsQuotesAndLinks() {
        String html = OutgoingHtml.clean("<p>Hi Maya,</p><p>Line one<br>line two</p><ul><li><p>floor plans</p></li><li><p>contract</p></li></ul>"
                + "<ol><li><p>first</p></li><li><p>second</p></li></ol><blockquote><p>Are you free?</p></blockquote>"
                + "<p>See <a href=\"https://example.com/menu\">the menu</a> or <a href=\"https://example.org\">https://example.org</a></p>");
        assertEquals("""
                Hi Maya,

                Line one
                line two

                - floor plans
                - contract

                1. first
                2. second

                > Are you free?

                See the menu (https://example.com/menu) or https://example.org
                """, OutgoingHtml.plainText(html));
    }

    @Test
    void onlyPastedDataImagesSurvive() {
        String clean = OutgoingHtml.clean("<p>a<img src=\"data:image/png;base64,iVBORw0KGgo=\" onerror=\"x()\"><img src=\"http://t.example/p.gif\"><img src=\"data:image/svg+xml;base64,AAAA\"></p>");
        org.junit.jupiter.api.Assertions.assertTrue(clean.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""), clean);
        org.junit.jupiter.api.Assertions.assertFalse(clean.contains("onerror") || clean.contains("t.example") || clean.contains("svg"), clean);
        assertEquals("a\n", OutgoingHtml.plainText(clean));
    }

    @Test
    void anEmptyMessageIsEmptyPlainText() {
        assertEquals("", OutgoingHtml.plainText(OutgoingHtml.clean("<p></p>")));
    }

    @Test
    void paragraphsEscapeWhatTheyQuote() {
        assertEquals("<p>a &lt;b&gt; &amp; \"c\"".replace("\"", "&quot;") + "<br>d</p><p>e</p>", OutgoingHtml.paragraphs("a <b> & \"c\"\nd\n\ne"));
    }
}
