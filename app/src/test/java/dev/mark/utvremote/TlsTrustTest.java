package dev.mark.utvremote;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.*;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

/** The site's real signed chain, verified offline against the official bundled CA. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=31)
public final class TlsTrustTest {
    private static final Date CHECK_DATE=Date.from(Instant.parse("2026-10-28T00:00:00Z"));
    private static final String ROOT_SHA256="4fa3126d8d3a11d1c4855a4f807cbad6cf919d3a5a88b03bea2c6372d93c40c9";
    private X509Certificate root() throws Exception {
        try(InputStream in=RuntimeEnvironment.getApplication().getResources().openRawResource(R.raw.globalsign_root_r46)) {
            return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }
    private X509Certificate fixture(String name) throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/tls/"+name)) {
            return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }
    private String hash(X509Certificate cert) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();
    }
    private void verify(X509Certificate anchor,Date date) throws Exception {
        List<X509Certificate> chain=Arrays.asList(fixture("u-tv-leaf.pem"),fixture("u-tv-intermediate.pem"));
        CertPath path=CertificateFactory.getInstance("X.509").generateCertPath(chain);
        PKIXParameters params=new PKIXParameters(Collections.singleton(new java.security.cert.TrustAnchor(anchor,null)));
        // Offline fixture test; application TLS validation is left to Android's normal implementation.
        params.setRevocationEnabled(false);params.setDate(date);
        CertPathValidator.getInstance("PKIX").validate(path,params);
    }
    @Test public void productionResourceIsTheOfficialSelfSignedCa() throws Exception {
        X509Certificate ca=root();assertEquals(ROOT_SHA256,hash(ca));assertTrue(ca.getBasicConstraints()>=0);
        assertEquals(ca.getSubjectX500Principal(),ca.getIssuerX500Principal());ca.verify(ca.getPublicKey());ca.checkValidity(CHECK_DATE);
    }
    @Test public void actualSiteChainFailsWithoutR46AndValidatesWithIt() throws Exception {
        try {verify(fixture("old-root-r3.pem"),CHECK_DATE);fail("Missing R46 must fail");}
        catch(CertPathValidatorException expected) { }
        verify(root(),CHECK_DATE);
    }
    @Test public void addingTheRootStillRejectsExpiredServerCertificates() throws Exception {
        Date expired=new Date(fixture("u-tv-leaf.pem").getNotAfter().getTime()+86400000L);
        try {verify(root(),expired);fail("An expired leaf must not become trusted");}
        catch(CertPathValidatorException expected) { }
    }
    @Test public void android12LoadsTheExtraAnchorOnlyForTheThreeSiteHosts() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        Class<?> sourceType=Class.forName("android.security.net.config.ManifestConfigSource");
        Object source=ReflectionHelpers.callConstructor(sourceType,ReflectionHelpers.ClassParameter.from(Context.class,app));
        Class<?> contract=Class.forName("android.security.net.config.ConfigSource");
        Class<?> appConfigType=Class.forName("android.security.net.config.ApplicationConfig");
        Object config=appConfigType.getConstructor(contract).newInstance(source);
        for(String host:Arrays.asList("u-tv.ru","www.u-tv.ru","img.u-tv.ru")) {
            Object domain=ReflectionHelpers.callInstanceMethod(config,"getConfigForHostname",ReflectionHelpers.ClassParameter.from(String.class,host));
            assertFalse((boolean)ReflectionHelpers.callInstanceMethod(domain,"isCleartextTrafficPermitted"));
            assertTrue(host,hasResourceCa(domain));
        }
        for(String host:Arrays.asList("example.com","u-tv.ru.evil.example","other.u-tv.ru","cdn.media1.ru","media1.ru","sub.cdn.media1.ru")) {
            Object domain=ReflectionHelpers.callInstanceMethod(config,"getConfigForHostname",ReflectionHelpers.ClassParameter.from(String.class,host));
            assertFalse((boolean)ReflectionHelpers.callInstanceMethod(domain,"isCleartextTrafficPermitted"));
            assertFalse(host,hasResourceCa(domain));
        }
    }
    private boolean hasResourceCa(Object config) {
        List<?> refs=ReflectionHelpers.getField(config,"mCertificatesEntryRefs");
        for(Object ref:refs) {
            Object source=ReflectionHelpers.getField(ref,"mSource");
            if(source.getClass().getSimpleName().equals("ResourceCertificateSource"))return true;
        }
        return false;
    }
}
