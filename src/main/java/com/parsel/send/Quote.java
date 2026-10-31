package com.parsel.send;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/** Turns the cached (already cleaned) HTML of a message into the plain text a reply quotes. */
final class Quote {

    private static final String BLOCKS = "p, div, li, tr, h1, h2, h3, h4, h5, h6, blockquote, pre, table, ul, ol";

    private Quote() {}

    /** Plain text with the line breaks of the original kept: paragraphs, list items and {@code <br>} each start a new line. */
    static String text(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("style, script, head").remove();
        for (Element br : doc.select("br")) {
            br.after(new TextNode("\n"));
        }
        for (Element block : doc.select(BLOCKS)) {
            block.before(new TextNode("\n"));
            block.after(new TextNode("\n"));
        }
        String text = doc.body().wholeText().replace(' ', ' ');
        return text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** Puts "> " in front of every line. */
    static String indent(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            out.append(line.isEmpty() ? ">" : "> " + line).append('\n');
        }
        return out.toString();
    }
}
