package ge.magti.portal.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Port of diffing.py -- structure-aware, XSS-safe HTML diff for Quill
 * article revisions. Diffs the TEXT content of block elements only; output
 * markup is rebuilt from escaped text, so tags can never be split and
 * injected HTML can never execute, same guarantee as the Python source.
 *
 * <p>Two library swaps, not literal ports: jsoup replaces BeautifulSoup for
 * block-element extraction (jsoup auto-wraps fragments in html/body, so
 * every parse here reads from {@code doc.body()} to approximate
 * BeautifulSoup's non-wrapping fragment parsing). java-diff-utils' Myers
 * algorithm replaces difflib.SequenceMatcher's Ratcliff-Obershelp algorithm
 * for the actual sequence diff -- {@link #computeOpcodes} reconstructs the
 * "equal" gaps java-diff-utils' Patch omits (it only returns actual
 * changes) into the same {op, i1, i2, j1, j2} shape SequenceMatcher.
 * get_opcodes() returns, so the block/word diff loops below stay
 * structurally close to diffing.py's. The migration doc's own acceptance
 * criterion for this endpoint is identical ins/del semantics, not
 * identical markup -- a different diff algorithm satisfies that.
 */
public final class HtmlDiffer {

    private static final String[] BLOCK_TAGS = {
            "p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "td", "th"
    };
    private static final String BLOCK_SELECTOR = String.join(", ", BLOCK_TAGS);

    private static final String INS_TEMPLATE =
            "<ins class=\"bg-green-200/60 dark:bg-green-900/60 no-underline rounded px-1 font-semibold text-green-950 dark:text-green-200\">%s</ins>";
    private static final String DEL_TEMPLATE =
            "<del class=\"bg-red-200/60 dark:bg-red-900/60 line-through rounded px-1 text-red-950 dark:text-red-300\">%s</del>";

    private static final String EQUAL_ROW_TEMPLATE =
            "<div class=\"diff-line diff-equal flex items-start gap-2 px-3 py-1.5 text-gray-600 dark:text-zinc-400 border-l-2 border-transparent\">"
                    + "<span class=\"select-none text-gray-300 dark:text-zinc-700 w-4 font-mono text-center\">•</span>"
                    + "<span class=\"flex-1\">%s</span></div>";
    private static final String DELETE_ROW_TEMPLATE =
            "<div class=\"diff-line diff-delete flex items-start gap-2 border-l-2 border-red-500 bg-red-50/40 dark:bg-red-950/20 px-3 py-1.5 rounded-r my-1 text-red-900 dark:text-red-200\">"
                    + "<span class=\"select-none text-red-400 font-bold w-4 font-mono text-center\">-</span>"
                    + "<span class=\"flex-1\">%s</span></div>";
    private static final String INSERT_ROW_TEMPLATE =
            "<div class=\"diff-line diff-insert flex items-start gap-2 border-l-2 border-green-500 bg-green-50/40 dark:bg-green-950/20 px-3 py-1.5 rounded-r my-1 text-green-900 dark:text-green-200\">"
                    + "<span class=\"select-none text-green-400 font-bold w-4 font-mono text-center\">+</span>"
                    + "<span class=\"flex-1\">%s</span></div>";
    private static final String MODIFIED_ROW_TEMPLATE =
            "<div class=\"diff-line diff-modified flex items-start gap-2 border-l-2 border-blue-500 bg-blue-50/20 dark:bg-blue-950/10 px-3 py-1.5 rounded-r my-1 text-gray-800 dark:text-zinc-200\">"
                    + "<span class=\"select-none text-blue-400 font-bold w-4 font-mono text-center\">✎</span>"
                    + "<span class=\"flex-1\">%s</span></div>";

    private HtmlDiffer() {
    }

    public static DiffResult diffHtml(String oldHtml, String newHtml) {
        List<String> a = blocks(oldHtml);
        List<String> b = blocks(newHtml);
        List<DiffOpcode> opcodes = computeOpcodes(a, b);

        List<String> rows = new ArrayList<>();
        int added = 0;
        int removed = 0;
        for (DiffOpcode oc : opcodes) {
            switch (oc.op()) {
                case "equal" -> {
                    for (String block : a.subList(oc.i1(), oc.i2())) {
                        rows.add(EQUAL_ROW_TEMPLATE.formatted(escape(block)));
                    }
                }
                case "delete" -> {
                    removed += oc.i2() - oc.i1();
                    for (String block : a.subList(oc.i1(), oc.i2())) {
                        rows.add(DELETE_ROW_TEMPLATE.formatted(escape(block)));
                    }
                }
                case "insert" -> {
                    added += oc.j2() - oc.j1();
                    for (String block : b.subList(oc.j1(), oc.j2())) {
                        rows.add(INSERT_ROW_TEMPLATE.formatted(escape(block)));
                    }
                }
                case "replace" -> {
                    added += oc.j2() - oc.j1();
                    removed += oc.i2() - oc.i1();
                    int span = Math.max(oc.i2() - oc.i1(), oc.j2() - oc.j1());
                    for (int k = 0; k < span; k++) {
                        String oldBlock = oc.i1() + k < oc.i2() ? a.get(oc.i1() + k) : "";
                        String newBlock = oc.j1() + k < oc.j2() ? b.get(oc.j1() + k) : "";
                        rows.add(MODIFIED_ROW_TEMPLATE.formatted(wordDiff(oldBlock, newBlock)));
                    }
                }
                default -> {
                }
            }
        }
        return new DiffResult(String.join("\n", rows), added, removed);
    }

    /** Port of _word_diff: inline word-level diff of two block strings into safe HTML. */
    static String wordDiff(String oldText, String newText) {
        List<String> o = splitWords(oldText);
        List<String> n = splitWords(newText);
        List<DiffOpcode> opcodes = computeOpcodes(o, n);

        List<String> parts = new ArrayList<>();
        for (DiffOpcode oc : opcodes) {
            switch (oc.op()) {
                case "equal" -> parts.add(escape(String.join(" ", o.subList(oc.i1(), oc.i2()))));
                case "delete" -> parts.add(DEL_TEMPLATE.formatted(escape(String.join(" ", o.subList(oc.i1(), oc.i2())))));
                case "insert" -> parts.add(INS_TEMPLATE.formatted(escape(String.join(" ", n.subList(oc.j1(), oc.j2())))));
                case "replace" -> {
                    parts.add(DEL_TEMPLATE.formatted(escape(String.join(" ", o.subList(oc.i1(), oc.i2())))));
                    parts.add(INS_TEMPLATE.formatted(escape(String.join(" ", n.subList(oc.j1(), oc.j2())))));
                }
                default -> {
                }
            }
        }
        return String.join(" ", parts.stream().filter(p -> !p.isEmpty()).toList());
    }

    /** Port of _blocks: flattens a Quill document into an ordered list of block text strings. */
    static List<String> blocks(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml == null ? "" : rawHtml);
        Element root = doc.body();
        Elements nodes = root.select(BLOCK_SELECTOR);
        if (nodes.isEmpty()) {
            String text = blockText(root);
            return text.isEmpty() ? List.of() : List.of(text);
        }
        List<String> out = new ArrayList<>();
        for (Element node : nodes) {
            String text = blockText(node);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    /** Port of _block_text: text of a block plus structural tokens for links/media. */
    static String blockText(Element node) {
        List<String> parts = new ArrayList<>();
        String text = node.text();
        if (!text.isEmpty()) {
            parts.add(text);
        }
        for (Element a : node.select("a")) {
            String href = a.attr("href").strip();
            if (!href.isEmpty()) {
                parts.add("[Link: " + href + "]");
            }
        }
        for (Element img : node.select("img")) {
            String src = img.attr("src").strip();
            String alt = img.attr("alt").strip();
            if (!src.isEmpty()) {
                String token = "[Image: " + src + "]";
                if (!alt.isEmpty()) {
                    token += " [Alt: " + alt + "]";
                }
                parts.add(token);
            }
        }
        return String.join(" ", parts);
    }

    /** Mirrors Python's bare str.split(): whitespace-run splitting, empty input -> empty list (not [""]). */
    private static List<String> splitWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.asList(text.trim().split("\\s+"));
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }

    /**
     * Reconstructs SequenceMatcher-shaped {@code (op, i1, i2, j1, j2)}
     * opcodes from java-diff-utils' Patch, which (unlike SequenceMatcher.
     * get_opcodes()) only reports actual changes -- the "equal" gaps
     * between/around them are filled in here.
     */
    private static List<DiffOpcode> computeOpcodes(List<String> a, List<String> b) {
        Patch<String> patch = DiffUtils.diff(a, b);
        List<DiffOpcode> opcodes = new ArrayList<>();
        int i = 0;
        int j = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int i1 = delta.getSource().getPosition();
            int j1 = delta.getTarget().getPosition();
            if (i1 > i) {
                opcodes.add(new DiffOpcode("equal", i, i1, j, j1));
            }
            int i2 = i1 + delta.getSource().size();
            int j2 = j1 + delta.getTarget().size();
            String op = switch (delta.getType()) {
                case DELETE -> "delete";
                case INSERT -> "insert";
                case CHANGE -> "replace";
                default -> "equal";
            };
            opcodes.add(new DiffOpcode(op, i1, i2, j1, j2));
            i = i2;
            j = j2;
        }
        if (i < a.size() || j < b.size()) {
            opcodes.add(new DiffOpcode("equal", i, a.size(), j, b.size()));
        }
        return opcodes;
    }
}
