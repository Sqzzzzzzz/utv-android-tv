package dev.mark.utvremote;
import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class PosterLoaderTest {
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void originalsKeepDetailWhileOversizedImagesAreBounded() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Bitmap image = Bitmap.createBitmap(1600,900,Bitmap.Config.ARGB_8888);
        image.compress(Bitmap.CompressFormat.PNG,100,bytes);
        Bitmap decoded = PosterLoader.decode(bytes.toByteArray());
        assertNotNull(decoded); assertEquals(1600,decoded.getWidth()); assertEquals(900,decoded.getHeight());
        bytes.reset(); image = Bitmap.createBitmap(3840,2160,Bitmap.Config.ARGB_8888);
        image.compress(Bitmap.CompressFormat.PNG,100,bytes); decoded = PosterLoader.decode(bytes.toByteArray());
        assertNotNull(decoded); assertEquals(1920,decoded.getWidth()); assertEquals(1080,decoded.getHeight());
        assertNull(PosterLoader.decode(new byte[]{1,2,3}));
    }
    @Test public void cacheIdentityChangesWithEpisodeOrPublishedThumbnail() {
        File dir = new File("cache");
        File first = PosterLoader.cacheFile(dir,"episode1|poster1");
        assertEquals(first,PosterLoader.cacheFile(dir,"episode1|poster1"));
        assertNotEquals(first,PosterLoader.cacheFile(dir,"episode2|poster1"));
        assertNotEquals(first,PosterLoader.cacheFile(dir,"episode1|poster2"));
        assertEquals(dir,first.getParentFile());
    }
    @Test public void imageRequestsAndRedirectsStayOnTheSupportedHttpsSources() throws Exception {
        assertTrue(PosterLoader.allowed(new URL("https://img.u-tv.ru/u/image.webp")));
        assertTrue(PosterLoader.allowed(new URL("https://cdn.media1.ru/static/videoplatform/posters/video/1/a.png")));
        assertFalse(PosterLoader.allowed(new URL("https://img.u-tv.ru.evil.test/image.png")));
        assertFalse(PosterLoader.allowed(new URL("https://user@img.u-tv.ru/image.png")));
        assertFalse(PosterLoader.allowed(new URL("http://img.u-tv.ru/image.png")));
        assertFalse(PosterLoader.allowed(new URL("https://cdn.media1.ru/videos/1/master.m3u8")));
    }
}
