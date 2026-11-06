package io.github.b077as.parsel.send;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The HTML of a message the user wrote. The page is untrusted input, so it is cleaned again here before anything is sent:
 * only the tags and the few style properties the composer can make survive, links must be http, https or mailto, and
 * nothing that loads or runs (scripts, forms, frames) gets through. The plain-text twin of the message is made
 * from the cleaned HTML, so the two parts always say the same thing.
 */
final class OutgoingHtml {

    private static final Set<String> KEEP = Set.of("p", "br", "b", "strong", "i", "em", "u", "s", "strike", "del", "ul", "ol", "li",
            "blockquote", "pre", "code", "h2", "h3", "a", "span", "hr", "div");
    /** Dropped together with everything inside them; any other unknown tag is only unwrapped (its text stays). */
    private static final Set<String> DROP = Set.of("script", "style", "head", "title", "iframe", "frame", "object", "embed", "svg", "math",
            "form", "input", "button", "select", "textarea", "video", "audio", "canvas", "link", "meta", "template", "noscript");
    /** The only images that survive: pasted pictures, kept inside the message as data and sent as inline parts (never a remote address). */
    static final Pattern DATA_IMAGE = Pattern.compile("data:image/(png|jpeg|gif|webp);base64,([A-Za-z0-9+/]+={0,2})");
    private static final Set<String> STYLED = Set.of("p", "h2", "h3", "span", "li", "div", "ul", "ol");

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}|rgba?\\([0-9 ,.%]{1,40}\\)|[a-zA-Z]{3,20}");
    private static final Pattern FAMILY = Pattern.compile("[A-Za-z0-9 ,'\"\\-]{1,100}");
    private static final Set<String> ALIGN = Set.of("left", "right", "center", "justify");

    /** Quote and code block look, written inline because mail clients do not read the style sheets of a message. */
    private static final String QUOTE_STYLE = "margin:0 0 0.7em 0.8ex;border-left:2px solid #cccccc;padding-left:1ex";
    private static final String CODE_STYLE = "margin:0 0 0.7em 0;padding:8px 10px;background:#f4f4f2;font-family:Consolas,'Courier New',monospace;white-space:pre-wrap";

    private OutgoingHtml() {}

    /** The cleaned HTML, ready to be the text/html part of a message. */
    static String clean(String html) {
        Document doc = Jsoup.parseBodyFragment(html == null ? "" : html);
        doc.outputSettings().prettyPrint(false);
        cleanChildren(doc.body());
        for (Element quote : doc.select("blockquote")) {
            quote.attr("style", QUOTE_STYLE);
        }
        for (Element pre : doc.select("pre")) {
            pre.attr("style", CODE_STYLE);
        }
        return doc.body().html();
    }

    private static void cleanChildren(Element parent) {
        for (Node child : new ArrayList<>(parent.childNodes())) {
            if (!(child instanceof Element element)) {
                if (!(child instanceof TextNode)) {
                    child.remove(); // comments, processing instructions
                }
                continue;
            }
            String name = element.tagName().toLowerCase(Locale.ROOT);
            if (name.equals("img")) {
                String src = element.attr("src").strip();
                if (DATA_IMAGE.matcher(src).matches()) {
                    String width = element.attr("width").strip();
                    element.clearAttributes();
                    element.attr("src", src);
                    if (width.matches("[0-9]{1,4}") && Integer.parseInt(width) > 0) {
                        element.attr("width", width); // the height follows from the style, so the picture keeps its shape
                    }
                    element.attr("style", "max-width:100%;height:auto");
                } else {
                    element.remove();
                }
                continue;
            }
            if (DROP.contains(name)) {
                element.remove();
                continue;
            }
            cleanChildren(element);
            if (name.equals("mark")) {
                element.tagName("span"); // the highlight: the background colour is all that is wanted of it
                name = "span";
            }
            if (!KEEP.contains(name)) {
                unwrap(element);
                continue;
            }
            if (name.equals("a") && !safeLink(element.attr("href"))) {
                unwrap(element);
                continue;
            }
            String href = name.equals("a") ? element.attr("href").strip() : null;
            String style = STYLED.contains(name) ? element.attr("style") : "";
            element.clearAttributes();
            if (href != null) {
                element.attr("href", href);
            }
            String filtered = filterStyle(style);
            if (!filtered.isEmpty()) {
                element.attr("style", filtered);
            }
        }
    }

    private static void unwrap(Element element) {
        List<Node> children = new ArrayList<>(element.childNodes());
        for (Node n : children) {
            element.before(n);
        }
        element.remove();
    }

    private static boolean safeLink(String href) {
        String h = href.strip().toLowerCase(Locale.ROOT);
        return h.startsWith("http://") || h.startsWith("https://") || h.startsWith("mailto:");
    }

    /** Keeps colour, background colour, font family and alignment with plain values; everything else is dropped. */
    static String filterStyle(String style) {
        StringBuilder out = new StringBuilder();
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).strip().replace("!important", "").strip();
            boolean ok = switch (property) {
                case "color", "background-color" -> COLOR.matcher(value).matches();
                case "font-family" -> FAMILY.matcher(value).matches();
                case "text-align" -> ALIGN.contains(value.toLowerCase(Locale.ROOT));
                default -> false;
            };
            if (ok) {
                out.append(out.length() == 0 ? "" : ";").append(property).append(':').append(value);
            }
        }
        return out.toString();
    }

    // ---- the plain-text twin ---------------------------------------------------------------------------------------

    /** Readable plain text for the cleaned HTML: paragraphs apart, list items with a bullet or number, quotes with "> ". */
    static String plainText(String cleanHtml) {
        Document doc = Jsoup.parseBodyFragment(cleanHtml);
        StringBuilder out = new StringBuilder();
        blocks(doc.body(), out);
        String text = out.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        return text.isEmpty() ? "" : text + "\n";
    }

    private static boolean isBlock(String tag) {
        return switch (tag) {
            case "p", "div", "ul", "ol", "blockquote", "pre", "h2", "h3", "hr" -> true;
            default -> false;
        };
    }

    /** Children of a container: runs of inline content become paragraphs, block children are written in their own way. */
    private static void blocks(Element parent, StringBuilder out) {
        StringBuilder inline = new StringBuilder();
        for (Node child : parent.childNodes()) {
            if (child instanceof Element e && isBlock(e.tagName())) {
                flush(inline, out);
                block(e, out);
            } else {
                inline(child, inline);
            }
        }
        flush(inline, out);
    }

    private static void flush(StringBuilder inline, StringBuilder out) {
        String text = inline.toString().strip();
        if (!text.isEmpty()) {
            out.append(text).append("\n\n");
        }
        inline.setLength(0);
    }

    private static void block(Element e, StringBuilder out) {
        switch (e.tagName()) {
            case "ul", "ol" -> {
                int number = 1;
                for (Element item : e.children()) {
                    if (!item.tagName().equals("li")) {
                        continue;
                    }
                    StringBuilder body = new StringBuilder();
                    blocks(item, body);
                    String marker = e.tagName().equals("ul") ? "- " : (number++) + ". ";
                    String text = body.toString().strip().replace("\n", "\n" + " ".repeat(marker.length()));
                    out.append(marker).append(text).append('\n');
                }
                out.append('\n');
            }
            case "blockquote" -> {
                StringBuilder body = new StringBuilder();
                blocks(e, body);
                for (String line : body.toString().strip().split("\n", -1)) {
                    out.append(line.isEmpty() ? ">" : "> " + line).append('\n');
                }
                out.append('\n');
            }
            case "pre" -> out.append(e.wholeText().strip()).append("\n\n");
            case "hr" -> out.append("----------\n\n");
            default -> blocks(e, out);
        }
    }

    private static void inline(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(text.getWholeText());
        } else if (node instanceof Element e) {
            if (e.tagName().equals("br")) {
                out.append('\n');
                return;
            }
            int start = out.length();
            for (Node child : e.childNodes()) {
                inline(child, out);
            }
            String href = e.tagName().equals("a") ? e.attr("href") : "";
            if (!href.isEmpty() && !out.substring(start).strip().equals(href) && !href.equals("mailto:" + out.substring(start).strip())) {
                out.append(" (").append(href).append(')');
            }
        }
    }

    /** Escapes plain text for use inside HTML. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Plain text as paragraphs: a blank line starts a new one, a single line break becomes {@code <br>}. */
    static String paragraphs(String text) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.strip().split("\\n{2,}")) {
            if (!paragraph.isBlank()) {
                out.append("<p>").append(escape(paragraph.strip()).replace("\n", "<br>")).append("</p>");
            }
        }
        return out.toString();
    }
}
