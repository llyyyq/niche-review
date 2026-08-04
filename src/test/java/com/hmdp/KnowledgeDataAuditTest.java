package com.hmdp;

import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only audit for blog-to-shop data quality before rebuilding RAG knowledge.
 *
 * <p>Run with:</p>
 * <pre>
 * mvn -Dtest=KnowledgeDataAuditTest -Dknowledge.audit.enabled=true test
 * </pre>
 *
 * <p>The report is written to {@code target/knowledge-data-audit.csv}. Rows marked
 * {@code REVIEW_OTHER_STORE_MENTION} are only review candidates. A blog may legitimately
 * compare more than one store, so this test never changes database or Qdrant data.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "knowledge.audit.enabled", matches = "true")
class KnowledgeDataAuditTest {

    private static final Pattern DECLARED_STORE_PATTERN = Pattern.compile(
            "(?:店名|店铺|商家)\\s*[：:]\\s*([^\\s，。；;、|]{2,40})");

    @Resource
    private IShopService shopService;

    @Resource
    private IBlogService blogService;

    @Test
    void auditBlogShopBindings() throws IOException {
        List<Shop> shops = shopService.list();
        List<Blog> blogs = blogService.list();
        Map<Long, Shop> shopsById = new HashMap<>();
        Map<Long, String> shopNamesById = new HashMap<>();
        List<StoreAlias> aliases = new ArrayList<>();
        for (Shop shop : shops) {
            shopsById.put(shop.getId(), shop);
            shopNamesById.put(shop.getId(), shop.getName());
            aliases.addAll(aliasesFor(shop));
        }
        aliases.sort(Comparator.comparingInt((StoreAlias alias) -> alias.value.length()).reversed());

        List<AuditRow> rows = new ArrayList<>();
        for (Blog blog : blogs) {
            Shop attachedShop = shopsById.get(blog.getShopId());
            String source = safe(blog.getTitle()) + "\n" + safe(blog.getContent());
            String normalizedSource = normalize(source);
            Set<Long> mentionedShopIds = findMentionedShopIds(normalizedSource, aliases);
            Set<Long> declaredShopIds = findDeclaredShopIds(source, aliases);
            rows.add(toAuditRow(blog, attachedShop, mentionedShopIds, declaredShopIds, shopNamesById));
        }

        rows.sort(Comparator.comparing(AuditRow::severity).reversed()
                .thenComparing(AuditRow::status)
                .thenComparing(AuditRow::blogId));
        Path report = Paths.get("target", "knowledge-data-audit.csv");
        Files.createDirectories(report.getParent());
        Files.write(report, toCsv(rows).getBytes(StandardCharsets.UTF_8));

        long reviewCount = rows.stream().filter(AuditRow::needsReview).count();
        System.out.printf(Locale.ROOT,
                "Knowledge data audit completed: shops=%d, blogs=%d, reviewCandidates=%d, report=%s%n",
                shops.size(), blogs.size(), reviewCount, report.toAbsolutePath());
    }

    private AuditRow toAuditRow(Blog blog, Shop attachedShop, Set<Long> mentionedShopIds,
                                Set<Long> declaredShopIds, Map<Long, String> shopNamesById) {
        Long attachedId = blog.getShopId();
        boolean attachedMentioned = attachedId != null && mentionedShopIds.contains(attachedId);
        Set<Long> otherMentioned = without(mentionedShopIds, attachedId);
        Set<Long> otherDeclared = without(declaredShopIds, attachedId);
        String status;
        int severity;
        if (!otherDeclared.isEmpty()) {
            status = "REVIEW_DECLARED_STORE_MISMATCH";
            severity = 3;
        } else if (!otherMentioned.isEmpty() && !attachedMentioned) {
            status = "REVIEW_OTHER_STORE_MENTION";
            severity = 2;
        } else if (!otherMentioned.isEmpty()) {
            status = "REVIEW_MULTI_STORE_MENTION";
            severity = 1;
        } else if (attachedMentioned) {
            status = "SELF_STORE_MENTIONED";
            severity = 0;
        } else {
            status = "NO_STORE_NAME_MENTION";
            severity = 0;
        }
        return new AuditRow(
                blog.getId(), attachedId, attachedShop == null ? "<missing shop>" : attachedShop.getName(),
                status, severity, namesFor(mentionedShopIds, shopNamesById),
                namesFor(declaredShopIds, shopNamesById),
                compact(blog.getTitle()), compact(blog.getContent()));
    }

    private Set<Long> findMentionedShopIds(String text, List<StoreAlias> aliases) {
        Set<Long> ids = new LinkedHashSet<>();
        for (StoreAlias alias : aliases) {
            if (text.contains(alias.value)) {
                ids.add(alias.shopId);
            }
        }
        return ids;
    }

    private Set<Long> findDeclaredShopIds(String source, List<StoreAlias> aliases) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher matcher = DECLARED_STORE_PATTERN.matcher(source);
        while (matcher.find()) {
            ids.addAll(findMentionedShopIds(normalize(matcher.group(1)), aliases));
        }
        return ids;
    }

    private List<StoreAlias> aliasesFor(Shop shop) {
        String name = normalize(shop.getName());
        List<StoreAlias> aliases = new ArrayList<>();
        if (name.length() >= 2) {
            aliases.add(new StoreAlias(shop.getId(), name));
        }
        int leftParenthesis = firstParenthesisIndex(name);
        if (leftParenthesis >= 2) {
            aliases.add(new StoreAlias(shop.getId(), name.substring(0, leftParenthesis)));
        }
        return aliases;
    }

    private int firstParenthesisIndex(String value) {
        int ascii = value.indexOf('(');
        int fullWidth = value.indexOf('（');
        if (ascii < 0) {
            return fullWidth;
        }
        return fullWidth < 0 ? ascii : Math.min(ascii, fullWidth);
    }

    private Set<Long> without(Set<Long> source, Long excluded) {
        Set<Long> result = new LinkedHashSet<>(source);
        result.remove(excluded);
        return result;
    }

    private String namesFor(Set<Long> ids, Map<Long, String> shopNamesById) {
        if (ids.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Long id : ids) {
            names.add(id + ":" + shopNamesById.getOrDefault(id, "<missing shop>"));
        }
        return String.join(" | ", names);
    }

    private String toCsv(List<AuditRow> rows) {
        StringBuilder csv = new StringBuilder("\uFEFFblog_id,attached_shop_id,attached_shop_name,status,severity,mentioned_shops,declared_shops,title,content_preview\n");
        for (AuditRow row : rows) {
            appendCsv(csv, row.blogId, true);
            appendCsv(csv, row.attachedShopId, true);
            appendCsv(csv, row.attachedShopName, true);
            appendCsv(csv, row.status, true);
            appendCsv(csv, row.severity, true);
            appendCsv(csv, row.mentionedShops, true);
            appendCsv(csv, row.declaredShops, true);
            appendCsv(csv, row.title, true);
            appendCsv(csv, row.contentPreview, false);
            csv.append('\n');
        }
        return csv.toString();
    }

    private void appendCsv(StringBuilder csv, Object value, boolean appendComma) {
        String cell = value == null ? "" : String.valueOf(value);
        csv.append('"').append(cell.replace("\"", "\"\"")).append('"');
        if (appendComma) {
            csv.append(',');
        }
    }

    private String normalize(String value) {
        return safe(value)
                .replace('（', '(')
                .replace('）', ')')
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String compact(String value) {
        String normalized = safe(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class StoreAlias {
        private final Long shopId;
        private final String value;

        private StoreAlias(Long shopId, String value) {
            this.shopId = shopId;
            this.value = value;
        }
    }

    private static final class AuditRow {
        private final Long blogId;
        private final Long attachedShopId;
        private final String attachedShopName;
        private final String status;
        private final int severity;
        private final String mentionedShops;
        private final String declaredShops;
        private final String title;
        private final String contentPreview;

        private AuditRow(Long blogId, Long attachedShopId, String attachedShopName, String status,
                         int severity, String mentionedShops, String declaredShops,
                         String title, String contentPreview) {
            this.blogId = blogId;
            this.attachedShopId = attachedShopId;
            this.attachedShopName = attachedShopName;
            this.status = status;
            this.severity = severity;
            this.mentionedShops = mentionedShops;
            this.declaredShops = declaredShops;
            this.title = title;
            this.contentPreview = contentPreview;
        }

        private boolean needsReview() {
            return severity > 0;
        }

        private Long blogId() { return blogId; }

        private String status() { return status; }

        private int severity() { return severity; }
    }
}
