package dev.mark.utvremote;

import android.content.Context;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import org.jsoup.HttpStatusException;
import java.io.IOException;
import java.net.*;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-land-mdpi")
public final class NetworkDiagnosticsTest {
    @Test public void identifiesHttpTlsDnsTimeoutAndParsingInsteadOfBlamingInternet() {
        assertEquals("HTTP_403",NetworkDiagnostics.code(new HttpStatusException("Forbidden",403,"https://www.u-tv.ru/")));
        assertEquals("HTTPS_TLS",NetworkDiagnostics.code(new IOException("outer",new SSLHandshakeException("certificate"))));
        assertEquals("DNS",NetworkDiagnostics.code(new UnknownHostException("site")));
        assertEquals("TIMEOUT",NetworkDiagnostics.code(new SocketTimeoutException("timeout")));
        assertEquals("CATALOG_FORMAT",NetworkDiagnostics.code(new CatalogRepository.CatalogException("No episodes")));
        assertTrue(NetworkDiagnostics.summary(new HttpStatusException("Forbidden",403,"https://www.u-tv.ru/")).contains("ограничил"));
    }
    @Test public void playbackErrorsExplainTheStageRatherThanAlwaysBlamingInternet() {
        assertEquals("PLAYER_NOT_FOUND", NetworkDiagnostics.code(
                new CatalogRepository.PlayerUnavailableException("player absent")));
        assertEquals("STREAM_NOT_FOUND", NetworkDiagnostics.code(
                new CatalogRepository.StreamUnavailableException("stream absent")));
        assertTrue(NetworkDiagnostics.playbackSummary(
                new CatalogRepository.PlayerUnavailableException("player absent")).contains("PLAYER_NOT_FOUND"));
        assertTrue(NetworkDiagnostics.playbackSummary(
                new CatalogRepository.StreamUnavailableException("stream absent")).contains("STREAM_NOT_FOUND"));
        assertTrue(NetworkDiagnostics.playbackSummary(
                new SSLHandshakeException("certificate")).contains("HTTPS_TLS"));
        assertTrue(NetworkDiagnostics.playbackSummary(
                new HttpStatusException("Forbidden", 403, "https://www.u-tv.ru/")).contains("ограничил"));
    }
    @Test public void emptyCatalogShowsActualErrorCodeAndSavesDetailsForTheTv() {
        RuntimeEnvironment.getApplication().getSharedPreferences("watch",Context.MODE_PRIVATE).edit().clear().commit();
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class);
        ReflectionHelpers.setField(c.get(),"network",new OfflineExecutor());c.setup().visible();
        try {
            Exception error=new HttpStatusException("Forbidden",403,"https://www.u-tv.ru/shows/mama-v-16/");
            ReflectionHelpers.callInstanceMethod(c.get(),"showLoadFailure",ReflectionHelpers.ClassParameter.from(Exception.class,error));
            TextView subtitle=ReflectionHelpers.getField(c.get(),"subtitle"),footer=ReflectionHelpers.getField(c.get(),"footer");
            assertTrue(subtitle.getText().toString().contains("HTTP_403"));assertFalse(footer.getText().toString().contains("Проверь интернет"));
            assertTrue(((Button)ReflectionHelpers.getField(c.get(),"refresh")).isEnabled());
            String details=c.get().getSharedPreferences("watch",0).getString("lastNetworkError","");
            assertTrue(details.contains("HTTP_403"));assertTrue(details.contains("Каталог: Мама в 16"));assertTrue(details.contains("API 28"));
        } finally {c.pause().stop().destroy();}
    }
}
