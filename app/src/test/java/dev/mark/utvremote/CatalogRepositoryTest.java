package dev.mark.utvremote;

import org.jsoup.Jsoup;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public final class CatalogRepositoryTest {
    private static final String BASE = CatalogRepository.SHOW_URL;
    private String card(int season, int episode, String id) {
        return "<a href='/shows/mama-v-16/episodes/" + id + "/'>"
                + "<img src='https://img.u-tv.ru/poster.jpg' alt='Описание выпуска'>"
                + "<div class='timeVideo'>74 мин</div><div class='swiperGroup__name'>"
                + season + " сезон " + episode + " серия</div></a>";
    }
    @Test public void catalogSortsNumericallyAndDeduplicatesWithoutIncludingTrailers() {
        String html = card(6, 10, "610") + card(1, 1, "101") + card(6, 2, "602") + card(6, 2, "602")
                + "<a href='/shows/mama-v-16/episodes/trailer/'>Новый сезон. Анонс</a>";
        List<CatalogRepository.Episode> e = CatalogRepository.parseCatalog(Jsoup.parse(html, BASE));
        assertEquals(3, e.size()); assertEquals(1, e.get(0).season);
        assertEquals(2, e.get(1).number); assertEquals(10, e.get(2).number);
        assertEquals("https://www.u-tv.ru/shows/mama-v-16/episodes/602/", e.get(1).url);
        assertEquals("74 мин", e.get(1).duration);
        assertEquals("https://img.u-tv.ru/poster.jpg", e.get(1).image);
    }
    @Test public void responsivePosterSelectsSigned1200VariantIncluding2x() {
        String base = "https://img.u-tv.ru/u/storage/images/video/medium/poster.webp";
        String html = "<picture><source srcset='" + base + "@600x336_10_a.webp, "
                + base + "@1200x672_10_b.webp 2x' media='(max-width: 767px)'>"
                + "<source srcset='" + base + "@600x336_10_a.webp'>"
                + "<img src='" + base + "@300x168_10_c.webp'></picture>";
        assertEquals(base + "@1200x672_10_b.webp", CatalogRepository.posterUrl(
                Jsoup.parse(html, BASE).selectFirst("img")));
    }
    @Test public void responsivePosterRejectsForeignAndOversizedCandidatesAndKeepsFallback() {
        String fallback = "https://img.u-tv.ru/image.webp@300x168_10_a.webp";
        String html = "<picture><source srcset='https://evil.example/image@1200x672_a.webp, "
                + "https://img.u-tv.ru/image@4000x2000_a.webp, "
                + "http://img.u-tv.ru/image@1200x672_a.webp'>"
                + "<img data-src='" + fallback + "'></picture>";
        assertEquals(fallback, CatalogRepository.posterUrl(Jsoup.parse(html, BASE).selectFirst("img")));
    }
    @Test public void acceptsMissingImageAndDuration() {
        List<CatalogRepository.Episode> e = CatalogRepository.parseCatalog(Jsoup.parse(
                "<a href='/shows/mama-v-16/episodes/1/'>1 сезон 2 серия</a>", BASE));
        assertEquals(1, e.size()); assertEquals("", e.get(0).image); assertEquals("", e.get(0).duration);
    }
    @Test public void streamIsReadFromConfigurationAndKeepsQueryParameters() throws Exception {
        String expected = "https://cdn.media1.ru/videos/example/master.m3u8?token=abc&quality=auto";
        assertEquals(expected, CatalogRepository.parseStream("options.multisources=[{\"source\":\"" + expected + "\"}];"));
    }
    @Test public void escapedStreamIsSupported() throws Exception {
        assertEquals("https://cdn.media1.ru/videos/a/master.m3u8?a=1&b=2", CatalogRepository.parseStream(
                "{'source':'https:\\/\\/cdn.media1.ru\\/videos\\/a\\/master.m3u8?a=1\\u0026b=2'}"));
    }
    @Test(expected = IOException.class) public void missingStreamProducesRecoverableError() throws Exception {
        CatalogRepository.parseStream("<html>Видео удалено</html>");
    }
    @Test(expected = IOException.class) public void foreignSourceIsNotAccepted() throws Exception {
        CatalogRepository.parseStream("{\"source\":\"https://cdn.media1.ru.evil.example/videos/a/master.m3u8\"}");
    }
    @Test public void domainValidationRejectsLookalikesAndUnexpectedSchemes() {
        assertTrue(CatalogRepository.isSitePage(BASE));
        assertFalse(CatalogRepository.isSitePage("https://www.u-tv.ru.evil.example/shows/mama-v-16/"));
        assertFalse(CatalogRepository.isSitePage("http://www.u-tv.ru/shows/mama-v-16/"));
        assertFalse(CatalogRepository.isMediaUrl("https://user@cdn.media1.ru/videos/a/master.m3u8"));
        assertFalse(CatalogRepository.isMediaUrl("file:///videos/a/master.m3u8"));
        assertTrue(CatalogRepository.isSitePage(Show.MAMENKIN.url()));
        assertFalse(CatalogRepository.isSitePage("https://www.u-tv.ru/shows/mamenkin-synok-extra/"));
        assertFalse(CatalogRepository.isSitePage("https://www.u-tv.ru/shows/mamenkin-synok/%2e%2e/other/"));
        assertFalse(CatalogRepository.isSitePage("https://www.u-tv.ru/shows/unknown/"));
    }
    @Test public void secondShowCatalogKeepsOnlyItsEpisodesAndAllTwelveInFirstSeason() {
        StringBuilder html = new StringBuilder(card(1, 1, "101"));
        for (int n=1; n<=12; n++) html.append("<a href='/shows/mamenkin-synok/episodes/")
                .append(200+n).append("/'><div class='swiperGroup__name'>1 сезон ").append(n).append(" серия</div></a>");
        html.append("<a href='/shows/mamenkin-synok/episodes/trailer/'>Новый сезон. Анонс</a>");
        List<CatalogRepository.Episode> episodes = CatalogRepository.parseCatalog(Jsoup.parse(html.toString(), Show.MAMENKIN.url()), Show.MAMENKIN);
        assertEquals(12, episodes.size()); assertEquals(12, episodes.get(11).number);
        assertTrue(episodes.stream().allMatch(e -> Show.fromUrl(e.url) == Show.MAMENKIN));
    }
    @Test public void media1PlayerIsPreferredWhenOtherProviderLinksAreAlsoPresent() throws Exception {
        String player = "https://cdn.media1.ru/videos/example?player_id=1241&ad_id=935";
        String html = "<div class='project__frame'><a href='https://www.ivi.ru/watch/example/1'>Иви</a></div>"
                + "<iframe src='" + player.replace("&", "&amp;") + "'></iframe>";
        assertEquals(player, CatalogRepository.parsePlayerUrl(Jsoup.parse(html, Show.MAMENKIN.url())));
    }
    @Test public void exclusiveIviEpisodeProducesAnExplicitAvailabilityMessage() throws Exception {
        try {
            CatalogRepository.parsePlayerUrl(Jsoup.parse("<div class='project__frame'><a href='https://www.ivi.ru/watch/mamenkin-syinok/1'>Смотреть</a></div>", Show.MAMENKIN.url()));
            fail("An external provider must not be treated as a playable Media1 episode");
        } catch (CatalogRepository.ExternalVideoException ex) { assertTrue(ex.getMessage().contains("Иви")); }
    }
    @Test(expected = IOException.class) public void untrustedPlayerUrlIsRejected() throws Exception {
        CatalogRepository.parsePlayerUrl(Jsoup.parse("<iframe src='https://cdn.media1.ru.evil.example/videos/a'></iframe>", Show.MAMENKIN.url()));
    }
    @Test public void originalPosterComesFromPlayerMetadataAndKeepsItsExactUrl() throws Exception {
        String url = "https://cdn.media1.ru/static/videoplatform/posters/video/123/original.png";
        assertEquals(url, CatalogRepository.parsePoster(Jsoup.parse("<meta name='og:image' content='"+url+"'>")));
        assertEquals(url, CatalogRepository.parsePoster(Jsoup.parse("<meta property='og:image' content='/static/videoplatform/posters/video/123/original.png'>", "https://cdn.media1.ru/videos/1")));
    }
    @Test public void originalPosterRejectsLookalikesCredentialsAndOtherPaths() {
        assertFalse(CatalogRepository.isOriginalPoster("https://cdn.media1.ru.evil.test/static/videoplatform/posters/video/1/a.png"));
        assertFalse(CatalogRepository.isOriginalPoster("https://user@cdn.media1.ru/static/videoplatform/posters/video/1/a.png"));
        assertFalse(CatalogRepository.isOriginalPoster("http://cdn.media1.ru/static/videoplatform/posters/video/1/a.png"));
        assertFalse(CatalogRepository.isOriginalPoster("https://cdn.media1.ru/videos/1/master.m3u8"));
    }
    @Test(expected = IOException.class) public void missingOriginalPosterLeavesTheThumbnailAvailable() throws Exception {
        CatalogRepository.parsePoster(Jsoup.parse("<meta name='og:image' content='https://foreign.test/a.png'>"));
    }

}
