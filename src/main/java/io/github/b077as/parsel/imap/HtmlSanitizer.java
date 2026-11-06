package io.github.b077as.parsel.imap;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.regex.Pattern;

/**
 * Cleans the HTML of received mail before it is stored. This is the first of three layers: the page then shows it in a
 * frame with no scripts, and a CSP that blocks the network (see {@code MessageBody.vue}). Scripts, event handlers, forms,
 * frames, objects, meta refresh and {@code javascript:} links do not survive here.
 *
 * <p>Inline {@code style} attributes are kept, because most mail relies on them to look right.
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("center", "font", "hr", "s", "tt", "big", "address", "article", "section", "header", "footer", "main", "nav", "figure", "figcaption", "abbr", "mark")
            .addAttributes(":all", "style", "class", "dir", "lang", "title", "align", "valign", "width", "height", "bgcolor", "color", "border",
                    "cellpadding", "cellspacing", "colspan", "rowspan", "face", "size", "nowrap")
            .addProtocols("a", "href", "http", "https", "mailto", "tel")
            .addProtocols("img", "src", "http", "https", "data")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .addEnforcedAttribute("a", "target", "_blank");

    private static final Pattern REMOTE_URL_IN_STYLE = Pattern.compile("(?i)url\\(\\s*['\"]?\\s*(https?:)?//");

    private HtmlSanitizer() {}

    /** @return the cleaned body HTML (no {@code <html>} or {@code <body>} wrapper) */
    public static String clean(String html) {
        Document dirty = Jsoup.parse(html);
        // Without this, writing the body back out collapses the line breaks of a plain-text mail (shown with white-space:pre-wrap).
        dirty.outputSettings().prettyPrint(false);
        String body = Jsoup.clean(dirty.body().html(), "", SAFELIST, new Document.OutputSettings().prettyPrint(false));
        return body.strip();
    }

    /** Whether the cleaned HTML would load something from the network: a remote image, or a remote url() in a style. */
    public static boolean hasRemoteContent(String cleanedHtml) {
        Document doc = Jsoup.parseBodyFragment(cleanedHtml);
        for (var image : doc.select("img[src]")) {
            String src = image.attr("src").strip().toLowerCase();
            if (src.startsWith("http:") || src.startsWith("https:") || src.startsWith("//")) {
                return true;
            }
        }
        for (var element : doc.select("[style]")) {
            if (REMOTE_URL_IN_STYLE.matcher(element.attr("style")).find()) {
                return true;
            }
        }
        return false;
    }

    /** Plain text of some HTML, for the one-line preview in the list. */
    public static String text(String cleanedHtml) {
        return Jsoup.parseBodyFragment(cleanedHtml).text();
    }
}
