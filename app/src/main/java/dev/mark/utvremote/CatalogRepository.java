package dev.mark.utvremote;


import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads public site pages on demand. No fixed video URLs or bundled episodes. */
public final class CatalogRepository {
    public static final String SHOW_URL = Show.MAMA.url();
    private static final Pattern EPISODE = Pattern.compile("(\\d+)\\s*сезон\\s*(\\d+)\\s*серия", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SOURCE = Pattern.compile("[\"']source[\"']\\s*:\\s*[\"']([^\"']+)[\"']");

    public static final class Episode {
        public final int season, number;
        public final String url, image, duration, detail;
        public Episode(int season, int number, String url, String image, String duration, String detail) {
            this.season = season; this.number = number; this.url = url;
            this.image = image; this.duration = duration; this.detail = detail;
        }
        public String title() { return season + " сезон · " + number + " серия"; }
    }

    public List<Episode> loadCatalog() throws IOException {
        return loadCatalog(Show.MAMA);
    }
    public List<Episode> loadCatalog(Show show) throws IOException {
        Document page = fetch(show.url());
        List<Episode> result = parseCatalog(page, show);
        if (result.isEmpty()) throw new CatalogException("Не удалось найти серии. Возможно, сайт изменился.");
        // Refuse a silently truncated season instead of presenting missing episodes as complete.
        for (Element season : page.select("[data-season][data-total]")) {
            String tab = season.attr("data-tab");
            if (!tab.matches("season-\\d+")) continue;
            int number = Integer.parseInt(tab.substring(7));
            int total;
            try { total = Integer.parseInt(season.attr("data-total")); }
            catch (NumberFormatException ignored) { continue; }
            long actual = result.stream().filter(e -> e.season == number).count();
            if (actual < total) throw new CatalogException("Сайт выдал неполный список " + number + " сезона. Обнови каталог позже.");
        }
        return result;
    }

    static List<Episode> parseCatalog(Document page) {
        return parseCatalog(page, Show.MAMA);
    }
    static List<Episode> parseCatalog(Document page, Show show) {
        List<Episode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element anchor : page.select("a[href*=" + show.path() + "episodes/]")) {
            Element name = anchor.selectFirst(".swiperGroup__name");
            Matcher match = EPISODE.matcher(name != null ? name.text() : anchor.text());
            if (!match.find()) continue;
            String url = anchor.absUrl("href");
            if (Show.fromUrl(url) != show || !seen.add(url)) continue;
            Element image = anchor.selectFirst("img");
            String poster = posterUrl(image);
            Element duration = anchor.selectFirst(".timeVideo");
            result.add(new Episode(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    url, poster, duration == null ? "" : duration.text(), image == null ? "" : image.attr("alt")));
        }
        result.sort(Comparator.comparingInt((Episode e) -> e.season).thenComparingInt(e -> e.number));
        return result;
    }

    /** Use the site's signed responsive image URLs, including its 2x variants. */
    static String posterUrl(Element image) {
        if (image == null) return "";
        String fallback = image.absUrl("src");
        if (fallback.isEmpty()) fallback = image.absUrl("data-src");
        Element picture = image.closest("picture");
        String best = fallback;
        int bestWidth = posterWidth(best);
        if (picture == null) return best;
        for (Element source : picture.select("source[srcset], img[srcset]")) {
            for (String entry : source.attr("srcset").split(",")) {
                String candidate = entry.trim().split("\\s+")[0];
                try { candidate = URI.create(image.baseUri()).resolve(candidate).toString(); }
                catch (IllegalArgumentException ignored) { continue; }
                int width = posterWidth(candidate);
                if (width > bestWidth && width <= 1200) {
                    best = candidate;
                    bestWidth = width;
                }
            }
        }
        return best;
    }
    private static int posterWidth(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"img.u-tv.ru".equals(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return 0;
            Matcher size = Pattern.compile("@(\\d{1,5})x\\d+_").matcher(uri.getPath());
            return size.find() ? Integer.parseInt(size.group(1)) : 0;
        } catch (IllegalArgumentException ignored) { return 0; }
    }

    public String resolvePoster(String episodeUrl) throws IOException {
        if (!isSitePage(episodeUrl)) throw new IOException("Неверный адрес серии.");
        return parsePoster(fetch(parsePlayerUrl(fetch(episodeUrl))));
    }
    static String parsePoster(Document playerPage) throws IOException {
        for (Element meta : playerPage.select("meta[property=og:image], meta[name=og:image]")) {
            String url = meta.absUrl("content");
            if (isOriginalPoster(url)) return url;
        }
        throw new IOException("Оригинальная обложка недоступна.");
    }

    static boolean isOriginalPoster(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "cdn.media1.ru".equals(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath().startsWith("/static/videoplatform/posters/video/");
        } catch (IllegalArgumentException ignored) { return false; }
    }

    public String resolveStream(String episodeUrl) throws IOException {
        if (!isSitePage(episodeUrl)) throw new IOException("Неверный адрес серии.");
        Document page = fetch(episodeUrl);
        return parseStream(fetch(parsePlayerUrl(page)).html());
    }

    static String parsePlayerUrl(Document page) throws IOException {
        for (Element frame : page.select("iframe[src]")) {
            String playerUrl = frame.absUrl("src");
            if (!isMediaUrl(playerUrl)) continue;
            return playerUrl;
        }
        for (Element link : page.select(".project__frame a[href]")) {
            try {
                String host = URI.create(link.absUrl("href")).getHost();
                if ("www.ivi.ru".equals(host) || "ivi.ru".equals(host))
                    throw new ExternalVideoException("Этот выпуск на сайте доступен через Иви.\nВстроенное видео пока недоступно.");
            } catch (IllegalArgumentException ignored) { }
        }
        throw new IOException("У этой серии другой плеер или видео пока недоступно.");
    }

    static final class CatalogException extends IOException {
        CatalogException(String message) { super(message); }
    }
    public static final class ExternalVideoException extends IOException {
        ExternalVideoException(String message) { super(message); }
    }

    static String parseStream(String playerHtml) throws IOException {
        Matcher match = SOURCE.matcher(playerHtml);
        while (match.find()) {
            String url = match.group(1).replace("\\/", "/").replace("\\u0026", "&");
            if (isMediaUrl(url) && URI.create(url).getPath().endsWith(".m3u8")) return url;
        }
        throw new IOException("Плеер не выдал поддерживаемый видеопоток.");
    }

    private Document fetch(String url) throws IOException {
        return SiteHttp.fetch(new java.net.URL(url));
    }

    static boolean isSitePage(String url) {
        return Show.fromUrl(url) != null;
    }
    static boolean isMediaUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "cdn.media1.ru".equals(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath().startsWith("/videos/");
        } catch (IllegalArgumentException ex) { return false; }
    }
}
