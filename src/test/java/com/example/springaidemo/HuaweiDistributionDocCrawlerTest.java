package com.example.springaidemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Crawl the Huawei "分发" section into Markdown files.
 *
 * <p>The site is rendered as a frontend shell, so this test uses Huawei's
 * official document APIs instead of scraping the empty shell HTML.</p>
 */
@Slf4j
public class HuaweiDistributionDocCrawlerTest {

    private static final String DEFAULT_OUTPUT_DIR = "target/huawei-distribution-docs";
    private static final String DOC_BASE_URL = "https://developer.huawei.com/consumer/cn/doc/app/";
    private static final String DOCUMENT_API = "https://developer.huawei.com/consumer/cn/documentPortal/getDocumentById";
    private static final String CATALOG_API = "https://developer.huawei.com/consumer/cn/documentPortal/getCatalogTree";
    private static final String CATALOG_SEED_OBJECT_ID = "agc-help-release-app-0000002271695230";
    private static final String CATALOG_NAME = "app";
    private static final String CATALOG_ROOT_NAME = "AppGallery Connect（HarmonyOS 5及以上）";
    private static final String ROOT_FOLDER_NAME = "分发";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PSEUDO_HEADING_MARKER = Pattern.compile("(?i)^\\[h[1-6]]\\s*");

    private static final List<BranchSpec> BRANCH_SPECS = List.of(
            new BranchSpec("发布应用", List.of("发布应用", "发布HarmonyOS应用")),
            new BranchSpec("发布元服务", List.of("发布应用", "发布元服务")),
            new BranchSpec("发布游戏", List.of("发布应用", "发布HarmonyOS游戏")),
            new BranchSpec("维护应用/元服务", List.of("维护应用"))
    );

    /**
     * Crawl the Huawei distribution documents.
     *
     * <p>Run with:
     * <pre>{@code
     * mvn -Dtest=HuaweiDistributionDocCrawlerTest -Dhuawei.scraper.enabled=true test
     * }</pre>
     */
    @Test
    @EnabledIfSystemProperty(named = "huawei.scraper.enabled", matches = "true")
    void crawlDistributionDocsToMarkdown() throws Exception {
        long startedAt = System.nanoTime();
        Path outputDir = Path.of(System.getProperty("huawei.scraper.outputDir", DEFAULT_OUTPUT_DIR))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(outputDir);

        log.info("Huawei distribution crawl started, outputDir={}", outputDir);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ObjectMapper objectMapper = new ObjectMapper();

        CatalogNode catalogRoot = fetchDistributionCatalogRoot(client, objectMapper);
        int writtenFiles = 0;

        for (BranchSpec branchSpec : BRANCH_SPECS) {
            CatalogNode branchRoot = findBranchNode(catalogRoot, branchSpec.catalogPath());
            if (branchRoot == null) {
                throw new IllegalStateException("未找到分支目录：" + branchSpec.displayName());
            }
            log.info("Branch crawl started, branch={}, seedDocument={}",
                    branchSpec.displayName(), branchRoot.relateDocument());
            writtenFiles += crawlBranch(client, objectMapper, outputDir, branchSpec, branchRoot);
            log.info("Branch crawl finished, branch={}", branchSpec.displayName());
        }

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.info("Huawei distribution crawl finished, writtenFiles={}, outputDir={}, elapsedMs={}",
                writtenFiles, outputDir, elapsedMs);
    }

    /**
     * Load the official catalog tree and locate the HarmonyOS 5+ AppGallery Connect root.
     */
    private CatalogNode fetchDistributionCatalogRoot(HttpClient client, ObjectMapper objectMapper)
            throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "catalogName", CATALOG_NAME,
                "objectId", CATALOG_SEED_OBJECT_ID,
                "showHide", "0",
                "language", "cn"
        ));

        HttpResponse<String> response = client.send(buildJsonPost(CATALOG_API, requestBody),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response, "catalog tree");

        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("获取目录树失败: " + root.path("message").asText());
        }

        List<CatalogNode> topNodes = new ArrayList<>();
        for (JsonNode node : root.path("value").path("catalogTreeList")) {
            topNodes.add(parseCatalogNode(node));
        }

        for (CatalogNode node : topNodes) {
            if (CATALOG_ROOT_NAME.equals(node.nodeName())) {
                return node;
            }
        }
        throw new IllegalStateException("未找到目录根节点：" + CATALOG_ROOT_NAME);
    }

    /**
     * Crawl one configured branch by walking the official catalog tree.
     */
    private int crawlBranch(HttpClient client,
                            ObjectMapper objectMapper,
                            Path outputDir,
                            BranchSpec branchSpec,
                            CatalogNode branchRoot) throws IOException, InterruptedException {
        List<String> branchFolder = List.of(ROOT_FOLDER_NAME, branchSpec.displayName());
        return crawlCatalogNode(client, objectMapper, outputDir, branchFolder, List.of(), branchRoot, true);
    }

    /**
     * Recursively crawl one catalog node and its children.
     */
    private int crawlCatalogNode(HttpClient client,
                                 ObjectMapper objectMapper,
                                 Path outputDir,
                                 List<String> baseHierarchy,
                                 List<String> relativePath,
                                 CatalogNode node,
                                 boolean isBranchRoot) throws IOException, InterruptedException {
        int writtenFiles = 0;
        if (!cleanText(node.relateDocument()).isBlank()) {
            long startedAt = System.nanoTime();
            List<String> hierarchy = buildOutputHierarchy(baseHierarchy, relativePath, node.nodeName());
            Path markdownDirectory = resolveSnapshotDirectory(outputDir, hierarchy);
            Path assetDirectory = outputDir.resolve("assets").resolve(sanitizeFileName(node.relateDocument()));
            String assetReferencePrefix = toMarkdownPath(markdownDirectory.relativize(assetDirectory).toString());
            PageSnapshot snapshot = fetchDocumentSnapshot(
                    client,
                    objectMapper,
                    node.relateDocument(),
                    assetDirectory,
                    assetReferencePrefix
            );
            writeSnapshot(outputDir, snapshot.withHierarchy(hierarchy));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            log.info("Page written, title={}, objectId={}, children={}, path={}, elapsedMs={}",
                    snapshot.title(),
                    node.relateDocument(),
                    node.children().size(),
                    hierarchy,
                    elapsedMs);
            writtenFiles++;
        }

        for (CatalogNode child : node.children()) {
            List<String> childRelativePath = new ArrayList<>(relativePath);
            childRelativePath.add(child.nodeName());
            writtenFiles += crawlCatalogNode(
                    client,
                    objectMapper,
                    outputDir,
                    baseHierarchy,
                    childRelativePath,
                    child,
                    false
            );
        }
        return writtenFiles;
    }

    /**
     * Convert one official document into a Markdown snapshot.
     */
    private PageSnapshot fetchDocumentSnapshot(HttpClient client,
                                               ObjectMapper objectMapper,
                                               String objectId,
                                               Path assetDirectory,
                                               String assetReferencePrefix) throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "objectId", objectId,
                "language", "cn"
        ));

        HttpResponse<String> response = client.send(buildJsonPost(DOCUMENT_API, requestBody),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response, "document " + objectId);

        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("获取文档失败, objectId=" + objectId
                    + ", message=" + root.path("message").asText());
        }

        JsonNode value = root.path("value");
        String title = cleanText(value.path("title").asText());
        String html = value.path("content").path("content").asText("");
        if (html.isBlank()) {
            throw new IllegalStateException("文档内容为空, objectId=" + objectId);
        }

        String url = DOC_BASE_URL + objectId;
        Document document = Jsoup.parse(html, url);
        localizeImages(client, document, assetDirectory, assetReferencePrefix);
        Element contentRoot = document.body();
        String markdown = buildMarkdown(title, url, contentRoot, List.of());
        return new PageSnapshot(url, title, List.of(), markdown);
    }

    /**
     * Parse one catalog node from Huawei's catalog tree JSON.
     */
    private CatalogNode parseCatalogNode(JsonNode node) {
        List<CatalogNode> children = new ArrayList<>();
        JsonNode childrenNode = node.path("children");
        if (childrenNode.isArray()) {
            for (JsonNode child : childrenNode) {
                if (child.isObject()) {
                    children.add(parseCatalogNode(child));
                }
            }
        }
        return new CatalogNode(
                cleanText(node.path("nodeName").asText()),
                cleanText(node.path("relateDocument").asText()),
                children
        );
    }

    /**
     * Locate the target branch subtree by a node-name path.
     */
    private CatalogNode findBranchNode(CatalogNode root, List<String> path) {
        CatalogNode current = root;
        for (String expectedName : path) {
            CatalogNode next = null;
            for (CatalogNode child : current.children()) {
                if (expectedName.equals(child.nodeName())) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    /**
     * Build the output hierarchy used for folders and filenames.
     */
    private List<String> buildOutputHierarchy(List<String> baseHierarchy,
                                              List<String> relativePath,
                                              String fallbackTitle) {
        List<String> hierarchy = new ArrayList<>(baseHierarchy);
        if (relativePath.isEmpty()) {
            hierarchy.add(cleanText(fallbackTitle));
            return hierarchy;
        }
        hierarchy.addAll(relativePath);
        String last = hierarchy.get(hierarchy.size() - 1);
        if (last.isBlank()) {
            hierarchy.set(hierarchy.size() - 1, cleanText(fallbackTitle));
        }
        return hierarchy;
    }

    /**
     * Download remote images and rewrite image src attributes to local relative paths.
     */
    private void localizeImages(HttpClient client,
                                Document document,
                                Path assetDirectory,
                                String assetReferencePrefix) throws IOException, InterruptedException {
        int index = 1;
        for (Element image : document.select("img")) {
            String remoteUrl = firstNonBlank(
                    image.absUrl("src"),
                    image.absUrl("data-src"),
                    image.absUrl("data-original"),
                    image.absUrl("data-original-src"),
                    image.absUrl("data-lazy-src")
            );
            if (remoteUrl.isBlank()) {
                continue;
            }
            String admonitionTitle = admonitionTitle(remoteUrl);
            if (!admonitionTitle.isBlank()) {
                image.attr("data-crawler-admonition-title", admonitionTitle);
                continue;
            }
            if (isLeadingSmallImage(image)) {
                image.attr("data-crawler-admonition-title", "\u6CE8\u610F");
                continue;
            }
            String extension = imageExtension(remoteUrl);
            String filename = String.format(Locale.ROOT, "image-%03d%s", index++, extension);
            Path target = assetDirectory.resolve(filename);
            try {
                Files.createDirectories(assetDirectory);
                HttpRequest request = HttpRequest.newBuilder(URI.create(remoteUrl))
                        .timeout(TIMEOUT)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400) {
                    log.warn("Image download failed, status={}, url={}", response.statusCode(), remoteUrl);
                    continue;
                }
                Files.write(target, response.body());
                image.attr("src", assetReferencePrefix + "/" + filename);
            } catch (IllegalArgumentException | IOException ex) {
                log.warn("Image download failed, url={}, message={}", remoteUrl, ex.getMessage());
            }
        }
    }

    /**
     * Build Markdown from the official HTML fragment.
     */
    private String buildMarkdown(String title,
                                 String url,
                                 Element contentRoot,
                                 List<String> hierarchy) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(title).append("\n\n");
        markdown.append("- Source: ").append(url).append("\n");
        markdown.append("- Crawled-At: ").append(OffsetDateTime.now()).append("\n\n");
        if (!hierarchy.isEmpty()) {
            markdown.append("> Path: ").append(String.join(" / ", hierarchy)).append("\n\n");
        }

        for (Node node : contentRoot.childNodes()) {
            if (isDuplicateTitle(node, title)) {
                continue;
            }
            appendNodeMarkdown(node, markdown, 0);
        }
        return markdown.toString().replaceAll("\\n{3,}", "\n\n").trim() + "\n";
    }

    /**
     * Convert block nodes to Markdown.
     */
    private void appendNodeMarkdown(Node node, StringBuilder out, int listDepth) {
        if (node instanceof TextNode textNode) {
            String text = cleanText(textNode.text());
            if (!text.isBlank()) {
                appendBlock(out, text);
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1" -> appendBlock(out, "# " + cleanText(element.text()));
            case "h2" -> appendBlock(out, "## " + cleanText(element.text()));
            case "h3" -> appendBlock(out, "### " + cleanText(element.text()));
            case "h4" -> appendBlock(out, "#### " + cleanText(element.text()));
            case "h5" -> appendBlock(out, "##### " + cleanText(element.text()));
            case "h6" -> appendBlock(out, "###### " + cleanText(element.text()));
            case "p" -> {
                if (appendAdmonitionParagraph(element, out)) {
                    return;
                }
                StringBuilder block = new StringBuilder();
                for (Node child : element.childNodes()) {
                    if (child instanceof Element childElement && !isInlineTag(childElement.tagName())) {
                        appendBlock(out, block.toString());
                        block.setLength(0);
                        appendNodeMarkdown(child, out, listDepth);
                    } else {
                        appendInlineMarkdown(child, block);
                    }
                }
                appendBlock(out, block.toString());
            }
            case "div", "section", "article", "body" -> {
                if (appendAdmonitionBlock(element, out)) {
                    return;
                }
                for (Node child : element.childNodes()) {
                    appendNodeMarkdown(child, out, listDepth);
                }
            }
            case "pre" -> appendBlock(out, "```" + "\n" + element.text().stripTrailing() + "\n```");
            case "blockquote" -> appendBlock(out, "> " + cleanText(element.text()).replace("\n", "\n> "));
            case "ul" -> appendList(element, out, listDepth, false);
            case "ol" -> appendList(element, out, listDepth, true);
            case "table" -> appendBlock(out, convertTableToMarkdown(element));
            case "img" -> appendBlock(out, imageMarkdown(element));
            case "hr" -> appendBlock(out, "---");
            default -> {
                if (isSkippableTag(tag)) {
                    return;
                }
                if (isInlineTag(tag)) {
                    StringBuilder block = new StringBuilder();
                    appendInlineMarkdown(element, block);
                    if (!block.toString().isBlank()) {
                        out.append(block);
                    }
                } else {
                    for (Node child : element.childNodes()) {
                        appendNodeMarkdown(child, out, listDepth);
                    }
                }
            }
        }
    }

    /**
     * Convert list nodes to Markdown.
     */
    private void appendList(Element listElement, StringBuilder out, int depth, boolean ordered) {
        int index = 1;
        for (Element li : listElement.select("> li")) {
            String indent = "  ".repeat(Math.max(depth, 0));
            String prefix = ordered ? (index++) + ". " : "- ";
            StringBuilder line = new StringBuilder();
            List<Element> deferredBlocks = new ArrayList<>();
            for (Node child : li.childNodes()) {
                if (child instanceof Element childElement
                        && ("ul".equalsIgnoreCase(childElement.tagName())
                        || "ol".equalsIgnoreCase(childElement.tagName()))) {
                    continue;
                }
                if (child instanceof Element childElement
                        && !isInlineTag(childElement.tagName())
                        && !"img".equalsIgnoreCase(childElement.tagName())) {
                    deferredBlocks.add(childElement);
                    continue;
                }
                appendInlineMarkdown(child, line);
            }
            out.append(indent).append(prefix).append(line.toString().trim()).append("\n");
            for (Element block : deferredBlocks) {
                appendNodeMarkdown(block, out, depth + 1);
            }
            for (Element nested : li.select("> ul, > ol")) {
                appendList(nested, out, depth + 1, "ol".equalsIgnoreCase(nested.tagName()));
            }
        }
        out.append("\n");
    }

    /**
     * Convert inline nodes to Markdown.
     */
    private void appendInlineMarkdown(Node node, StringBuilder out) {
        if (node instanceof TextNode textNode) {
            out.append(cleanText(textNode.text()));
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "strong", "b" -> out.append("**").append(cleanText(element.text())).append("**");
            case "em", "i" -> out.append("*").append(cleanText(element.text())).append("*");
            case "code" -> out.append("`").append(element.text().trim()).append("`");
            case "img" -> out.append(imageMarkdown(element));
            case "a" -> {
                String text = cleanText(element.text());
                String href = normalizeUrl(element.absUrl("href"));
                if (!text.isBlank() && !href.isBlank()) {
                    out.append("[").append(text).append("](").append(href).append(")");
                } else if (!text.isBlank()) {
                    out.append(text);
                }
            }
            case "br" -> out.append("  \n");
            default -> {
                for (Node child : element.childNodes()) {
                    appendInlineMarkdown(child, out);
                }
            }
        }
    }

    /**
     * Convert an HTML table to a GitHub-flavored Markdown table.
     */
    private String convertTableToMarkdown(Element table) {
        List<Element> rows = table.select("> thead > tr, > tbody > tr, > tr");
        if (rows.isEmpty()) {
            rows = table.select("tr");
        }
        if (rows.isEmpty()) {
            return "";
        }

        List<List<String>> convertedRows = new ArrayList<>();
        for (Element row : rows) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("> th, > td")) {
                cells.add(tableCellMarkdown(cell));
            }
            if (!cells.isEmpty()) {
                convertedRows.add(cells);
            }
        }
        if (convertedRows.isEmpty()) {
            return "";
        }

        int columnCount = convertedRows.stream().mapToInt(List::size).max().orElse(0);
        if (columnCount == 0) {
            return "";
        }
        for (List<String> row : convertedRows) {
            while (row.size() < columnCount) {
                row.add("");
            }
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append(tableRow(convertedRows.get(0))).append("\n");
        markdown.append(tableSeparator(columnCount)).append("\n");
        for (int i = 1; i < convertedRows.size(); i++) {
            markdown.append(tableRow(convertedRows.get(i))).append("\n");
        }
        return markdown.toString().trim();
    }

    /**
     * Convert one table cell while preserving links, inline code, and images.
     */
    private String tableCellMarkdown(Element cell) {
        StringBuilder value = new StringBuilder();
        for (Node child : cell.childNodes()) {
            if (child instanceof Element childElement
                    && ("ul".equalsIgnoreCase(childElement.tagName())
                    || "ol".equalsIgnoreCase(childElement.tagName()))) {
                appendCompactList(childElement, value, "ol".equalsIgnoreCase(childElement.tagName()));
                continue;
            }
            appendInlineMarkdown(child, value);
        }
        return cleanTableCell(value.toString());
    }

    /**
     * Flatten a list that appears inside a table cell.
     */
    private void appendCompactList(Element listElement, StringBuilder out, boolean ordered) {
        int index = 1;
        for (Element li : listElement.select("> li")) {
            if (!out.isEmpty()) {
                out.append("<br>");
            }
            out.append(ordered ? index++ + ". " : "- ");
            StringBuilder line = new StringBuilder();
            for (Node child : li.childNodes()) {
                if (child instanceof Element childElement
                        && ("ul".equalsIgnoreCase(childElement.tagName())
                        || "ol".equalsIgnoreCase(childElement.tagName()))) {
                    continue;
                }
                appendInlineMarkdown(child, line);
            }
            out.append(cleanText(line.toString()));
        }
    }

    /**
     * Build one Markdown table row.
     */
    private String tableRow(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }

    /**
     * Build the Markdown table separator row.
     */
    private String tableSeparator(int columnCount) {
        return "| " + String.join(" | ", java.util.Collections.nCopies(columnCount, "---")) + " |";
    }

    /**
     * Escape table-cell content so pipes and line breaks do not break the table.
     */
    private String cleanTableCell(String text) {
        return cleanText(text)
                .replace("|", "\\|")
                .replace("\r\n", "<br>")
                .replace("\n", "<br>");
    }

    /**
     * Convert an image node to Markdown and keep remote images visible in Markdown previewers.
     */
    private String imageMarkdown(Element image) {
        if (isAdmonitionIcon(image)) {
            return "";
        }
        String src = firstNonBlank(
                image.attr("src"),
                image.absUrl("src"),
                image.absUrl("data-src"),
                image.absUrl("data-original"),
                image.absUrl("data-original-src"),
                image.absUrl("data-lazy-src")
        );
        if (src.isBlank()) {
            return "";
        }
        String alt = cleanText(firstNonBlank(image.attr("alt"), image.attr("title"), "image"));
        return "![" + alt.replace("]", "\\]") + "](" + normalizeUrl(src) + ")";
    }

    /**
     * Convert Huawei note/caution icon paragraphs to readable Markdown blockquotes.
     */
    private boolean appendAdmonitionParagraph(Element paragraph, StringBuilder out) {
        String title = leadingAdmonitionTitle(paragraph);
        if (title.isBlank()) {
            return false;
        }

        StringBuilder body = new StringBuilder();
        boolean skippedIcon = false;
        for (Node child : paragraph.childNodes()) {
            if (!skippedIcon && child instanceof Element childElement && isAdmonitionIcon(childElement)) {
                skippedIcon = true;
                continue;
            }
            appendInlineMarkdown(child, body);
        }

        String text = cleanText(body.toString());
        if (text.isBlank()) {
            appendBlock(out, "> **" + title + "**");
        } else {
            appendBlock(out, "> **" + title + "**\n>\n> " + text.replace("\n", "\n> "));
        }
        return true;
    }

    /**
     * Convert Huawei admonition containers such as div.caution into Markdown blockquotes.
     */
    private boolean appendAdmonitionBlock(Element element, StringBuilder out) {
        String title = admonitionTitle(element);
        if (title.isBlank()) {
            return false;
        }

        Element body = directChildByClassPart(element, "body");
        if (body == null) {
            body = element;
        }

        StringBuilder bodyMarkdown = new StringBuilder();
        for (Node child : body.childNodes()) {
            appendNodeMarkdown(child, bodyMarkdown, 0);
        }
        appendBlock(out, blockquote(title, bodyMarkdown.toString()));
        return true;
    }

    /**
     * Format an admonition as a Markdown blockquote while keeping nested lists readable.
     */
    private String blockquote(String title, String body) {
        StringBuilder out = new StringBuilder();
        out.append("> **").append(title).append("**");
        String cleaned = body == null ? "" : body.trim();
        if (!cleaned.isBlank()) {
            out.append("\n>");
            for (String line : cleaned.split("\\R")) {
                if (line.isBlank()) {
                    out.append("\n>");
                } else {
                    out.append("\n> ").append(line);
                }
            }
        }
        return out.toString();
    }

    /**
     * Find a direct child whose class contains the given fragment.
     */
    private Element directChildByClassPart(Element element, String classPart) {
        for (Element child : element.children()) {
            if (child.className().toLowerCase(Locale.ROOT).contains(classPart)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Read an admonition title when the first visible paragraph item is a Huawei icon.
     */
    private String leadingAdmonitionTitle(Element paragraph) {
        for (Node child : paragraph.childNodes()) {
            if (child instanceof TextNode textNode && cleanText(textNode.text()).isBlank()) {
                continue;
            }
            if (child instanceof Element childElement && isAdmonitionIcon(childElement)) {
                return childElement.attr("data-crawler-admonition-title");
            }
            return "";
        }
        return "";
    }

    /**
     * Identify Huawei admonition icon images that should not be emitted as normal content images.
     */
    private boolean isAdmonitionIcon(Element element) {
        if (!"img".equalsIgnoreCase(element.tagName())) {
            return false;
        }
        if (!element.attr("data-crawler-admonition-title").isBlank()) {
            return true;
        }
        return !admonitionTitle(firstNonBlank(
                element.attr("src"),
                element.absUrl("src"),
                element.absUrl("data-src"),
                element.absUrl("data-original"),
                element.absUrl("data-original-src"),
                element.absUrl("data-lazy-src")
        )).isBlank();
    }

    /**
     * Map Huawei icon filenames to readable admonition titles.
     */
    private String admonitionTitle(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains("caution") || lower.contains("warning")) {
            return "\u6CE8\u610F";
        }
        if (lower.contains("note")) {
            return "\u8BF4\u660E";
        }
        return "";
    }

    /**
     * Read an admonition title from a Huawei semantic container class.
     */
    private String admonitionTitle(Element element) {
        String className = element.className().toLowerCase(Locale.ROOT);
        if (className.contains("caution") || className.contains("warning")) {
            return "\u6CE8\u610F";
        }
        if (className.contains("note")) {
            return "\u8BF4\u660E";
        }
        return "";
    }

    /**
     * Huawei sometimes renders the "注意" label as a small leading image without a semantic name.
     */
    private boolean isLeadingSmallImage(Element image) {
        int width = dimension(image, "originwidth", "width");
        int height = dimension(image, "originheight", "height");
        return width > 0
                && width <= 180
                && height > 0
                && height <= 90
                && isFirstVisibleChild(image);
    }

    /**
     * Parse a numeric image dimension from one of several possible attributes.
     */
    private int dimension(Element element, String... names) {
        for (String name : names) {
            String value = element.attr(name).replaceAll("[^0-9]", "");
            if (!value.isBlank()) {
                return Integer.parseInt(value);
            }
        }
        return 0;
    }

    /**
     * Check whether an element is the first visible child in its parent.
     */
    private boolean isFirstVisibleChild(Element element) {
        if (element.parent() == null) {
            return false;
        }
        for (Node child : element.parent().childNodes()) {
            if (child == element) {
                return true;
            }
            if (child instanceof TextNode textNode && cleanText(textNode.text()).isBlank()) {
                continue;
            }
            return false;
        }
        return false;
    }

    /**
     * Infer a stable image extension from the URL path.
     */
    private String imageExtension(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
            for (String extension : List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg")) {
                if (path.endsWith(extension)) {
                    return extension;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to png below.
        }
        return ".png";
    }

    /**
     * Pick the first visible string.
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Skip the body h1 because the Markdown file already has the document title.
     */
    private boolean isDuplicateTitle(Node node, String title) {
        if (!(node instanceof Element element)) {
            return false;
        }
        return "h1".equalsIgnoreCase(element.tagName()) && cleanText(element.text()).equals(cleanText(title));
    }

    /**
     * Write one Markdown file to disk.
     */
    private void writeSnapshot(Path outputDir, PageSnapshot snapshot) {
        try {
            List<String> hierarchy = snapshot.hierarchy();
            Path directory = resolveSnapshotDirectory(outputDir, hierarchy);
            Files.createDirectories(directory);
            String filename = sanitizeFileName(hierarchy.get(hierarchy.size() - 1)) + ".md";
            Files.writeString(directory.resolve(filename), snapshot.markdown(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write markdown for " + snapshot.url(), ex);
        }
    }

    /**
     * Resolve the directory where a snapshot Markdown file will be written.
     */
    private Path resolveSnapshotDirectory(Path outputDir, List<String> hierarchy) {
        Path directory = outputDir;
        for (int i = 0; i < hierarchy.size() - 1; i++) {
            directory = directory.resolve(sanitizeFileName(hierarchy.get(i)));
        }
        return directory;
    }

    /**
     * Normalize a filesystem path for Markdown references.
     */
    private String toMarkdownPath(String path) {
        return path.replace('\\', '/');
    }

    /**
     * Build a standard JSON POST request.
     */
    private HttpRequest buildJsonPost(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Check the HTTP response status.
     */
    private void ensureSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() >= 400) {
            throw new IOException("Failed to fetch " + operation + ", status=" + response.statusCode());
        }
    }

    /**
     * Normalize a URL and tolerate invalid fragments from upstream HTML.
     */
    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String raw = url.trim().replace(" ", "%20");
        try {
            URI uri = new URI(raw);
            URI normalized = new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
            return normalized.toString();
        } catch (URISyntaxException ex) {
            return raw;
        }
    }

    /**
     * Collapse repeated whitespace.
     */
    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = WHITESPACE.matcher(text.replace('\u00A0', ' ')).replaceAll(" ").trim();
        return PSEUDO_HEADING_MARKER.matcher(cleaned).replaceFirst("").trim();
    }

    /**
     * Append a Markdown block if it has visible content.
     */
    private void appendBlock(StringBuilder out, String block) {
        String cleaned = block == null ? "" : block.trim();
        if (!cleaned.isBlank()) {
            out.append(cleaned).append("\n\n");
        }
    }

    /**
     * Determine whether a tag is inline.
     */
    private boolean isInlineTag(String tag) {
        return Set.of("span", "a", "strong", "b", "em", "i", "code", "small", "label")
                .contains(tag.toLowerCase(Locale.ROOT));
    }

    /**
     * Determine whether a tag should be skipped.
     */
    private boolean isSkippableTag(String tag) {
        return Set.of("script", "style", "noscript", "svg", "button", "input")
                .contains(tag.toLowerCase(Locale.ROOT));
    }

    /**
     * Produce a filesystem-safe filename.
     */
    private String sanitizeFileName(String text) {
        String cleaned = cleanText(text)
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\.$", "")
                .trim();
        return cleaned.isBlank() ? "untitled" : cleaned;
    }

    /**
     * One branch we want to export under the root "分发" folder.
     */
    private record BranchSpec(String displayName, List<String> catalogPath) {
    }

    /**
     * One node inside Huawei's catalog tree.
     */
    private record CatalogNode(String nodeName, String relateDocument, List<CatalogNode> children) {
    }

    /**
     * Markdown snapshot for one page.
     */
    private record PageSnapshot(String url, String title, List<String> hierarchy, String markdown) {

        private PageSnapshot withHierarchy(List<String> newHierarchy) {
            String updatedMarkdown = buildMarkdownWithPath(markdown, newHierarchy);
            return new PageSnapshot(url, title, newHierarchy, updatedMarkdown);
        }

        private String buildMarkdownWithPath(String originalMarkdown, List<String> newHierarchy) {
            if (newHierarchy.isEmpty()) {
                return originalMarkdown;
            }
            String[] lines = originalMarkdown.split("\\R", -1);
            List<String> rebuilt = new ArrayList<>();
            boolean inserted = false;
            for (String line : lines) {
                rebuilt.add(line);
                if (!inserted && line.startsWith("- Crawled-At: ")) {
                    rebuilt.add("");
                    rebuilt.add("> Path: " + String.join(" / ", newHierarchy));
                    inserted = true;
                }
            }
            return String.join("\n", rebuilt).replaceAll("\\n{3,}", "\n\n").trim() + "\n";
        }
    }
}
