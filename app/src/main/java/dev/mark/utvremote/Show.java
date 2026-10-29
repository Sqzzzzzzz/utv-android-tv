package dev.mark.utvremote;

import java.net.URI;

/** The small, explicit list of shows supported by this personal client. */
public enum Show {
    MAMA("mama-v-16", "Мама в 16"),
    MAMENKIN("mamenkin-synok", "Маменькин сынок");

    public final String slug, title;
    Show(String slug, String title) { this.slug = slug; this.title = title; }
    public String url() { return "https://www.u-tv.ru/shows/" + slug + "/"; }
    String path() { return "/shows/" + slug + "/"; }

    // Keep the original show's keys so an update preserves existing history.
    String key(String name) { return this == MAMA ? name : name + ":" + slug; }

    static Show fromSlug(String slug) {
        for (Show show : values()) if (show.slug.equals(slug)) return show;
        return null;
    }
    static Show fromUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"www.u-tv.ru".equals(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            String path = uri.getPath();
            if (path == null || path.indexOf('\\') >= 0) return null;
            for (String segment : path.split("/"))
                if (segment.equals(".") || segment.equals("..")) return null;
            for (Show show : values()) if (path.startsWith(show.path())) return show;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
