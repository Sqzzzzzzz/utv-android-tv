package dev.mark.utvremote;

import org.jsoup.HttpStatusException;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public final class SiteHttpTest {
    private static final String HTML = "<html><meta charset='utf-8'><a href='episodes/1/'>1 сезон 1 серия</a></html>";
    private static final class Response extends HttpURLConnection {
        byte[] body = HTML.getBytes(StandardCharsets.UTF_8);
        String encoding, redirect;
        int status = 200;
        boolean disconnected;
        IOException failure;
        Response() throws Exception { super(new URL("https://www.u-tv.ru/shows/mama-v-16/")); }
        @Override public int getResponseCode() throws IOException { if (failure != null) throw failure; return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public String getContentEncoding() { return encoding; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? redirect : null; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    private URL url(List<Response> responses) throws Exception {
        return new URL(null,"https://www.u-tv.ru/shows/mama-v-16/",new URLStreamHandler() {
            int index;
            @Override protected URLConnection openConnection(URL u) throws IOException {
                if (index >= responses.size()) throw new IOException("Unexpected request"); return responses.get(index++);
            }
        });
    }
    @Test public void nativeRequestHasBrowserHeadersAndParsesUtf8WithOriginalBase() throws Exception {
        Response r = new Response();
        org.jsoup.nodes.Document page=SiteHttp.fetch(url(Collections.singletonList(r)));
        assertEquals("1 сезон 1 серия",page.selectFirst("a").text());
        assertEquals("https://www.u-tv.ru/shows/mama-v-16/episodes/1/",page.selectFirst("a").absUrl("href"));
        assertEquals(SiteHttp.USER_AGENT,r.getRequestProperty("User-Agent"));assertEquals("gzip",r.getRequestProperty("Accept-Encoding"));
        assertFalse(r.getUseCaches());assertFalse(r.getInstanceFollowRedirects());assertTrue(r.disconnected);
    }
    @Test public void gzipResponsesAreDecodedBeforeParsing() throws Exception {
        Response r = new Response();r.encoding="gzip";ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(r.body);}r.body=out.toByteArray();
        assertTrue(SiteHttp.fetch(url(Collections.singletonList(r))).text().contains("1 сезон"));assertTrue(r.disconnected);
    }
    @Test public void httpErrorsKeepTheirStatusAndCloseTheConnection() throws Exception {
        Response r=new Response();r.status=403;
        try {SiteHttp.fetch(url(Collections.singletonList(r)));fail();}
        catch(HttpStatusException ex){assertEquals(403,ex.getStatusCode());}assertTrue(r.disconnected);
    }
    @Test public void followsRelativeHttpsRedirectAndClosesBothConnections() throws Exception {
        Response first=new Response(),second=new Response();first.status=302;first.redirect="?view=all";
        assertTrue(SiteHttp.fetch(url(Arrays.asList(first,second))).text().contains("серия"));
        assertTrue(first.disconnected);assertTrue(second.disconnected);
    }
    @Test public void rejectsCleartextAndForeignRedirectsWithoutContactingThem() throws Exception {
        for(String redirect:Arrays.asList("http://www.u-tv.ru/shows/mama-v-16/","https://other.example/page")) {
            Response first=new Response(),second=new Response();first.status=302;first.redirect=redirect;
            try {SiteHttp.fetch(url(Arrays.asList(first,second)));fail();}catch(IOException expected){ }
            assertTrue(first.disconnected);assertFalse(second.disconnected);
        }
    }
    @Test public void redirectLoopsAreBounded() throws Exception {
        List<Response> responses=new ArrayList<>();
        for(int i=0;i<6;i++){Response r=new Response();r.status=302;r.redirect="?again="+i;responses.add(r);}
        try{SiteHttp.fetch(url(responses));fail();}catch(IOException expected){ }
        for(Response r:responses)assertTrue(r.disconnected);
    }
    @Test public void decompressedSizeIsBounded() throws Exception {
        Response r=new Response();r.encoding="gzip";ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(new byte[3*1024*1024+1]);}r.body=out.toByteArray();
        try{SiteHttp.fetch(url(Collections.singletonList(r)));fail();}catch(SiteHttp.ResponseTooLargeException expected){ }
        assertTrue(r.disconnected);
    }
    @Test public void socketFailuresRemainAvailableToDiagnostics() throws Exception {
        Response r=new Response();r.failure=new SocketTimeoutException("Timed out");
        try{SiteHttp.fetch(url(Collections.singletonList(r)));fail();}catch(SocketTimeoutException expected){ }
        assertTrue(r.disconnected);
    }
}
