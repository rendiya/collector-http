/* Copyright 2018-2019 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.collector.http.fetch.impl;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.collector.http.doc.HttpDocument;
import com.norconex.collector.http.server.TestServer;
import com.norconex.collector.http.server.TestServerBuilder;
import com.norconex.commons.lang.OSResource;
import com.norconex.commons.lang.file.WebFile;
import com.norconex.commons.lang.io.CachedStreamFactory;

//TODO if EDGE fails, log an error and Assume false (ignore the test).

//TODO merge http client with document fetcher.
// have 1 doc fetcher and 1 http fetcher that can be the same or different.
// have ability to specify different fetchers for different URL patterns.
//@Ignore

public class WebDriverHttpFetcherTest  {

    private static final Logger LOG =
            LoggerFactory.getLogger(WebDriverHttpFetcherTest.class);

    private static TestServer server = new TestServerBuilder()
            .addPackage("server/js-rendered")
            .addServlet(new HttpServlet() {
                private static final long serialVersionUID = 1L;
                @Override
                protected void doGet(HttpServletRequest req,
                        HttpServletResponse resp)
                        throws ServletException, IOException {
                    resp.addHeader("TEST_KEY", "test_value");
                    resp.getWriter().write("HTTP headers test. "
                            + "TEST_KEY should be found in HTTP headers");
                }
            }, "/headers")
            .build();

//  https://chromedriver.storage.googleapis.com/2.43/chromedriver_linux64.zip
//  https://chromedriver.storage.googleapis.com/2.43/chromedriver_mac64.zip
    private static final Path chromeDriverPath = new OSResource<Path>()
            .win(WebFile.create("https://chromedriver.storage.googleapis.com/"
                    + "73.0.3683.20/chromedriver_win32.zip!/chromedriver.exe",
                    "chromedriver-73.0.3683.20.exe"))
            .get();

//  https://github.com/mozilla/geckodriver/releases/download/v0.23.0/geckodriver-v0.23.0-win64.zip
//  https://developer.mozilla.org/en-US/docs/Web/WebDriver
//  https://ftp.mozilla.org/pub/firefox/releases/55.0.3/
    private static final Path firefoxDriverPath = new OSResource<Path>()
            .win(WebFile.create(
                    "https://github.com/mozilla/geckodriver/releases/download/"
                  + "v0.23.0/geckodriver-v0.23.0-win64.zip!/geckodriver.exe",
                    "geckodriver-0.23.exe"))
            .get();

//    private static final Path edgeDriverPath = new OSResource<Path>()
//            .win(WebFile.create("https://download.microsoft.com/download/F/8/A/"
//                    + "F8AF50AB-3C3A-4BC4-8773-DC27B32988DD/"
//                    + "MicrosoftWebDriver.exe",
//                    "edgedriver-6.17134.exe"))
//            .get();

//    private final WebDriverBrowser browser;
//    private final Path driverPath;


//    public WebDriverHttpFetcherTest(WebDriverBrowser browser, Path driverPath) {
//        super();
//        this.browser = browser;
//        this.driverPath = driverPath;
//    }

//    static Stream<Object[]> browsersProvider() {
//        return Stream.of(
//                new Object[]{WebDriverBrowser.FIREFOX, firefoxDriverPath},
//                new Object[]{WebDriverBrowser.CHROME, chromeDriverPath}
////              {WebDriverBrowser.EDGE, edgeDriverPath},
//        );
//    }
    static Stream<WebDriverHttpFetcher> browsersProvider() {
        return Stream.of(
                createFetcher(WebDriverBrowser.FIREFOX, firefoxDriverPath),
                createFetcher(WebDriverBrowser.CHROME, chromeDriverPath)
//              {WebDriverBrowser.EDGE, edgeDriverPath},
        );
    }

    @BeforeAll
    public static void beforeClass() throws IOException {
        server.start();
    }
    @AfterAll
    public static void afterClass() throws IOException {
        server.stop();
    }
//    @BeforeEach
//    public void before() throws IOException {
//        Assumptions.assumeTrue(
//                isDriverPresent(driverPath),
//                "SKIPPING: No driver for " + browser.name());
//    }


    @ExtensionTest
    public void testFetchingJsGeneratedContent(
            WebDriverHttpFetcher fetcher) throws IOException {
        try {
            // simulate crawler startup
            fetcher.crawlerStartup(null);
            HttpDocument doc = fetch(fetcher, "/");
            LOG.debug("'/' META: " + doc.getMetadata());
            Assertions.assertTrue(IOUtils.toString(
                    doc.getInputStream(), StandardCharsets.UTF_8).contains(
                            "JavaScript-rendered!"));
        } finally {
            fetcher.crawlerShutdown(null);
        }
    }

    // Remove ignore to manually test that screenshots are generated
    @Disabled
    @ExtensionTest
    public void testTakeScreenshots(
            WebDriverHttpFetcher fetcher) throws IOException {

        WebDriverScreenshotHandler h = new WebDriverScreenshotHandler();
        h.setTargetDir(Paths.get("./target/screenshots"));
        h.setCssSelector("#applePicture");
        fetcher.setScreenshotHandler(h);

        try {
            fetcher.crawlerStartup(null);
            fetch(fetcher, "/apple.html");
        } finally {
            fetcher.crawlerShutdown(null);
        }
    }

    @ExtensionTest
    public void testFetchingHeadersUsingSniffer(
            WebDriverHttpFetcher fetcher) throws IOException {

        // Test picking up headers
        Assumptions.assumeTrue(
                isProxySupported(fetcher.getBrowser()),
                "SKIPPING: " + fetcher.getBrowser().name()
                + " does not support setting proxy to obtain headers.");

        WebDriverHttpSnifferConfig cfg = new WebDriverHttpSnifferConfig();
        fetcher.setHttpSnifferConfig(cfg);

        try {
            // simulate crawler startup
            fetcher.crawlerStartup(null);
            HttpDocument doc = fetch(fetcher, "/headers");
            LOG.debug("'/headers' META: " + doc.getMetadata());
            Assertions.assertEquals(
                    "test_value", doc.getMetadata().getString("TEST_KEY"));
        } finally {
            fetcher.crawlerShutdown(null);
        }
    }

    @ExtensionTest
    public void testPageScript(
            WebDriverHttpFetcher fetcher) throws IOException {

        fetcher.setPageScript(
                "document.getElementsByTagName('h1')[0].innerHTML='Melon';");
        try {
            fetcher.crawlerStartup(null);
            HttpDocument doc = fetch(fetcher, "/orange.html");

            String h1 = IOUtils.toString(doc.getInputStream(),
                    StandardCharsets.UTF_8).replaceFirst(
                            "(?s).*<h1>(.*?)</h1>.*", "$1");
            LOG.debug("New H1: " + h1);
            Assertions.assertEquals("Melon", h1);
        } finally {
            fetcher.crawlerShutdown(null);
        }
    }

    @ExtensionTest
    public void testResolvingUserAgent(
            WebDriverHttpFetcher fetcher) throws IOException {
        try {
            fetcher.crawlerStartup(null);
            String userAgent = fetcher.getUserAgent();
            LOG.debug("User agent: {}", userAgent);
            Assertions.assertTrue(
                    StringUtils.isNotBlank(userAgent),
                    "Could not resolve user agent.");
        } finally {
            fetcher.crawlerShutdown(null);
        }
    }

    private static WebDriverHttpFetcher createFetcher(
            WebDriverBrowser browser, Path driverPath) {
        WebDriverHttpFetcher fetcher = new WebDriverHttpFetcher();
        fetcher.setBrowser(browser);
        fetcher.setDriverPath(driverPath);
        return fetcher;
    }


    private HttpDocument fetch(WebDriverHttpFetcher fetcher, String urlPath) {
        HttpDocument doc = new HttpDocument(
                "http://localhost:" + server.getPort() + urlPath,
                new CachedStreamFactory(10000, 10000).newInputStream());
        /*HttpFetchResponse response = */ fetcher.fetchDocument(doc);
        return doc;
    }

//    private boolean isDriverPresent(Path driverPath) {
//        try {
//            return driverPath != null && driverPath.toFile().exists();
//        } catch (Exception e) {
//            LOG.debug("Could not verify driver presence at: {}. Error: {}",
//                    driverPath, e.getMessage());
//            return false;
//        }
//    }
    // Returns false for browsers not supporting setting proxies, which
    // is required to capture headers.
    private boolean isProxySupported(WebDriverBrowser browser) {
        return /*browser != WebDriverBrowser.EDGE
                && */ browser != WebDriverBrowser.CHROME;
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ParameterizedTest(name = "browser: {0}")
//    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("browsersProvider")
    @interface ExtensionTest {
    }
}