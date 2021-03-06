/* Copyright 2010-2020 Norconex Inc.
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
package com.norconex.collector.http.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.norconex.collector.core.CollectorException;
import com.norconex.collector.core.crawler.Crawler;
import com.norconex.collector.core.pipeline.importer.ImporterPipelineContext;
import com.norconex.collector.core.reference.CrawlReference;
import com.norconex.collector.core.reference.CrawlState;
import com.norconex.collector.http.HttpCollector;
import com.norconex.collector.http.doc.HttpDocument;
import com.norconex.collector.http.doc.HttpMetadata;
import com.norconex.collector.http.fetch.HttpFetchClient;
import com.norconex.collector.http.pipeline.committer.HttpCommitterPipeline;
import com.norconex.collector.http.pipeline.committer.HttpCommitterPipelineContext;
import com.norconex.collector.http.pipeline.importer.HttpImporterPipeline;
import com.norconex.collector.http.pipeline.importer.HttpImporterPipelineContext;
import com.norconex.collector.http.pipeline.queue.HttpQueuePipeline;
import com.norconex.collector.http.pipeline.queue.HttpQueuePipelineContext;
import com.norconex.collector.http.reference.HttpCrawlReference;
import com.norconex.collector.http.sitemap.ISitemapResolver;
import com.norconex.commons.lang.url.HttpURL;
import com.norconex.importer.doc.ImporterDocument;
import com.norconex.importer.response.ImporterResponse;
import com.norconex.jef5.status.JobStatusUpdater;
import com.norconex.jef5.suite.JobSuite;

/**
 * The HTTP Crawler.
 * @author Pascal Essiembre
 */
public class HttpCrawler extends Crawler {

    private static final Logger LOG =
            LoggerFactory.getLogger(HttpCrawler.class);

	private ISitemapResolver sitemapResolver;
	private HttpFetchClient fetchClient;

    /**
     * Constructor.
     * @param crawlerConfig HTTP crawler configuration
     * @param collector http collector this crawler belongs to
     */
	public HttpCrawler(
	        HttpCrawlerConfig crawlerConfig, HttpCollector collector) {
		super(crawlerConfig, collector);
	}

    @Override
    public HttpCrawlerConfig getCrawlerConfig() {
        return (HttpCrawlerConfig) super.getCrawlerConfig();
    }

    public HttpFetchClient getHttpFetchClient() {
        return fetchClient;
    }

    /**
     * @return the sitemapResolver
     */
    public ISitemapResolver getSitemapResolver() {
        return sitemapResolver;
    }

//    @Override
//    public void stop(JobStatus jobStatus, JobSuite suite) {
//        super.stop(jobStatus, suite);
//        if (sitemapResolver != null) {
//            sitemapResolver.stop();
//        }
//    }

    @Override
    protected void prepareExecution(
            JobStatusUpdater statusUpdater, JobSuite suite,
            boolean resume) {

        HttpCrawlerConfig cfg = getCrawlerConfig();

        logInitializationInformation();
        fetchClient = new HttpFetchClient(
                getStreamFactory(), cfg.getHttpFetchers(),
                cfg.getHttpFetchersMaxRetries(),
                cfg.getHttpFetchersRetryDelay());

        // We always initialize the sitemap resolver even if ignored
        // because sitemaps can be specified as start URLs.
//        if (cfg.getSitemapResolverFactory() != null) {
            this.sitemapResolver = cfg.getSitemapResolver();
//                    .createSitemapResolver(cfg, resume);
//        }

        if (!resume) {
            queueStartURLs();
        }
    }

    private void queueStartURLs() {
        int urlCount = 0;
        // Sitemaps must be first, since we favor explicit sitemap
        // referencing as oppose to let other methods guess for it.
        urlCount += queueStartURLsSitemaps();
        urlCount += queueStartURLsRegular();
        urlCount += queueStartURLsSeedFiles();
        urlCount += queueStartURLsProviders();
        if (LOG.isInfoEnabled()) {
            LOG.info("{} start URLs identified.",
                    NumberFormat.getNumberInstance().format(urlCount));
        }
    }

    private int queueStartURLsRegular() {
        List<String> startURLs = getCrawlerConfig().getStartURLs();
        for (String startURL : startURLs) {
            if (StringUtils.isNotBlank(startURL)) {
                executeQueuePipeline(
                        new HttpCrawlReference(startURL, 0));
            } else {
                LOG.debug("Blank start URL encountered, ignoring it.");
            }
        }
        return startURLs.size();
    }
    private int queueStartURLsSeedFiles() {
        List<Path> urlsFiles = getCrawlerConfig().getStartURLsFiles();

        int urlCount = 0;
        for (Path urlsFile : urlsFiles) {
            try (LineIterator it = IOUtils.lineIterator(
                    Files.newInputStream(urlsFile), StandardCharsets.UTF_8)) {
                while (it.hasNext()) {
                    String startURL = StringUtils.trimToNull(it.nextLine());
                    if (startURL != null && !startURL.startsWith("#")) {
                        executeQueuePipeline(
                                new HttpCrawlReference(startURL, 0));
                        urlCount++;
                    }
                }
            } catch (IOException e) {
                throw new CollectorException(
                        "Could not process URLs file: " + urlsFile, e);
            }
        }
        return urlCount;
    }
    private int queueStartURLsSitemaps() {
        List<String> sitemapURLs = getCrawlerConfig().getStartSitemapURLs();

        // There are sitemaps, process them. First group them by URL root
        MultiValuedMap<String, String> sitemapsPerRoots =
                new ArrayListValuedHashMap<>();
        for (String sitemapURL : sitemapURLs) {
            String urlRoot = HttpURL.getRoot(sitemapURL);
            sitemapsPerRoots.put(urlRoot, sitemapURL);
        }

        final MutableInt urlCount = new MutableInt();
        Consumer<HttpCrawlReference> urlConsumer = (ref) -> {
                executeQueuePipeline(ref);
                urlCount.increment();
        };
        // Process each URL root group separately
        for (String  urlRoot : sitemapsPerRoots.keySet()) {
            List<String> locations =
                    (List<String>) sitemapsPerRoots.get(urlRoot);
            if (sitemapResolver != null) {
                sitemapResolver.resolveSitemaps(
                        fetchClient, urlRoot, locations, urlConsumer, true);
            } else {
                LOG.error("Sitemap resolver is null. Sitemaps defined as "
                        + "start URLs cannot be resolved.");
            }
        }
        return urlCount.intValue();
    }

    private int queueStartURLsProviders() {
        List<IStartURLsProvider> providers =
                getCrawlerConfig().getStartURLsProviders();
        if (providers == null) {
            return 0;
        }
        int count = 0;
        for (IStartURLsProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            Iterator<String> it = provider.provideStartURLs();
            while (it.hasNext()) {
                executeQueuePipeline(new HttpCrawlReference(it.next(), 0));
                count++;
            }
        }
        return count;
    }

    private void logInitializationInformation() {
        LOG.info("RobotsTxt support: {}",
                !getCrawlerConfig().isIgnoreRobotsTxt());
        LOG.info("RobotsMeta support: {}",
                !getCrawlerConfig().isIgnoreRobotsMeta());
        LOG.info("Sitemap support: {}",
                !getCrawlerConfig().isIgnoreSitemap());
        LOG.info("Canonical links support: {}",
                !getCrawlerConfig().isIgnoreCanonicalLinks());

//        String userAgent = getCrawlerConfig().getUserAgent();
//        if (StringUtils.isBlank(userAgent)) {
//            LOG.info("{}: User-Agent: <None specified>", id);
//            LOG.debug("It is recommended you identify yourself to web sites "
//                    + "by specifying a user agent "
//                    + "(https://en.wikipedia.org/wiki/User_agent)");
//        } else {
//            LOG.info("{}: User-Agent: {}", id, userAgent);
//        }
    }

    @Override
    protected void executeQueuePipeline(
            CrawlReference crawlRef) {
        HttpCrawlReference httpData = (HttpCrawlReference) crawlRef;
        HttpQueuePipelineContext context = new HttpQueuePipelineContext(
                this, httpData);
        new HttpQueuePipeline().execute(context);
    }

    @Override
    protected ImporterDocument wrapDocument(
            CrawlReference crawlRef, ImporterDocument document) {
        return new HttpDocument(document);
    }

    @Override
    protected Class<? extends CrawlReference> getCrawlReferenceType() {
        return HttpCrawlReference.class;
    }


    @Override
    protected void initCrawlReference(CrawlReference crawlRef,
            CrawlReference cachedCrawlRef, ImporterDocument document) {
        HttpDocument doc = (HttpDocument) document;
        HttpCrawlReference httpData = (HttpCrawlReference) crawlRef;
        HttpCrawlReference cachedHttpData = (HttpCrawlReference) cachedCrawlRef;
        HttpMetadata metadata = doc.getMetadata();

        metadata.add(HttpMetadata.COLLECTOR_DEPTH, httpData.getDepth());
        metadataAddString(metadata, HttpMetadata.COLLECTOR_SM_CHANGE_FREQ,
                httpData.getSitemapChangeFreq());
        if (httpData.getSitemapLastMod() != null) {
            metadata.add(HttpMetadata.COLLECTOR_SM_LASTMOD,
                    httpData.getSitemapLastMod());
        }
        if (httpData.getSitemapPriority() != null) {
            metadata.add(HttpMetadata.COLLECTOR_SM_PRORITY,
                    httpData.getSitemapPriority());
        }

        // In case the crawl data supplied is from a URL was pulled from cache
        // since the parent was skipped and could not be extracted normally
        // with link information, we attach referrer data here if null
        // (but only if referrer reference is not null, which should never
        // be in this case as it is set by beforeFinalizeDocumentProcessing()
        // below.
        // We do not need to do this for sitemap information since the queue
        // pipeline takes care of (re)adding it.
        //TODO consider having a flag on CrawlData that says where it came
        //from so we know to initialize it properly.  Or... always
        //initialize some new crawl data from cache higher up?
        if (cachedHttpData != null && httpData.getReferrerReference() != null
                && Objects.equal(
                        httpData.getReferrerReference(),
                        cachedHttpData.getReferrerReference())) {
            if (httpData.getReferrerLinkTag() == null) {
                httpData.setReferrerLinkTag(
                        cachedHttpData.getReferrerLinkTag());
            }
            if (httpData.getReferrerLinkText() == null) {
                httpData.setReferrerLinkText(
                        cachedHttpData.getReferrerLinkText());
            }
            if (httpData.getReferrerLinkTitle() == null) {
                httpData.setReferrerLinkTitle(
                        cachedHttpData.getReferrerLinkTitle());
            }
        }

        // Add referrer data to metadata
        metadataAddString(metadata, HttpMetadata.COLLECTOR_REFERRER_REFERENCE,
                httpData.getReferrerReference());
        metadataAddString(metadata, HttpMetadata.COLLECTOR_REFERRER_LINK_TAG,
                httpData.getReferrerLinkTag());
        metadataAddString(metadata, HttpMetadata.COLLECTOR_REFERRER_LINK_TEXT,
                httpData.getReferrerLinkText());
        metadataAddString(metadata, HttpMetadata.COLLECTOR_REFERRER_LINK_TITLE,
                httpData.getReferrerLinkTitle());

        // Add possible redirect trail
        if (!httpData.getRedirectTrail().isEmpty()) {
            metadata.setList(HttpMetadata.COLLECTOR_REDIRECT_TRAIL,
                    httpData.getRedirectTrail());
        }
    }

    @Override
    protected ImporterResponse executeImporterPipeline(
            ImporterPipelineContext importerContext) {
        HttpImporterPipelineContext httpContext =
                new HttpImporterPipelineContext(importerContext);
        new HttpImporterPipeline(
                getCrawlerConfig().isKeepDownloads(),
                importerContext.isOrphan()).execute(httpContext);
        return httpContext.getImporterResponse();
    }

    @Override
    protected CrawlReference createEmbeddedCrawlReference(
            String embeddedReference, CrawlReference parentCrawlData) {
        return new HttpCrawlReference(embeddedReference,
                ((HttpCrawlReference) parentCrawlData).getDepth());
    }

    @Override
    protected void executeCommitterPipeline(Crawler crawler,
            ImporterDocument doc,
            CrawlReference crawlRef, CrawlReference cachedCrawlRef) {

        HttpCommitterPipelineContext context = new HttpCommitterPipelineContext(
                (HttpCrawler) crawler, (HttpDocument) doc,
                (HttpCrawlReference) crawlRef, (HttpCrawlReference) cachedCrawlRef);
        new HttpCommitterPipeline().execute(context);
    }

    @Override
    protected void beforeFinalizeDocumentProcessing(
            CrawlReference crawlRef,
            ImporterDocument doc, CrawlReference cachedData) {

        // If URLs were not yet extracted, it means no links will be followed.
        // In case the referring document was skipped or has a bad status
        // (which can always be temporary), we should queue for processing any
        // referenced links from cache to make sure an attempt will be made to
        // re-crawl these "child" links and they will not be considered orphans.
        // Else, as orphans they could wrongfully be deleted, ignored, or
        // be re-assigned the wrong depth if linked from another, deeper, page.
        // See: https://github.com/Norconex/collector-http/issues/278


        HttpCrawlReference httpData = (HttpCrawlReference) crawlRef;
        HttpCrawlReference httpCachedData = (HttpCrawlReference) cachedData;

        //TODO improve this #533 hack in v3
        if (httpData.getState().isNewOrModified()
                && !httpData.getRedirectTrail().isEmpty()) {
            HttpImporterPipeline.GOOD_REDIRECTS.add(httpData.getReference());
        }

        // If never crawled before, URLs were extracted already, or cached
        // version has no extracted, URLs, abort now.
        if (cachedData == null
                || !httpData.getReferencedUrls().isEmpty()
                || httpCachedData.getReferencedUrls().isEmpty()) {
            return;
        }

        // Only continue if the document could not have extracted URLs because
        // it was skipped, or in a temporary invalid state that prevents
        // accessing child links normally.
        CrawlState state = crawlRef.getState();
        if (!state.isSkipped() && !state.isOneOf(
                CrawlState.BAD_STATUS, CrawlState.ERROR)) {
            return;
        }

        // OK, let's do this
        if (LOG.isDebugEnabled()) {
            LOG.debug("Queueing referenced URLs of {}",
                    crawlRef.getReference());
        }

        int childDepth = httpData.getDepth() + 1;
        List<String> referencedUrls = httpCachedData.getReferencedUrls();
        for (String url : referencedUrls) {

            HttpCrawlReference childData = new HttpCrawlReference(url, childDepth);
            childData.setReferrerReference(httpData.getReference());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Queueing skipped document's child: "
                        + childData.getReference());
            }
            executeQueuePipeline(childData);
        }
    }

    @Override
    protected void markReferenceVariationsAsProcessed(
            CrawlReference crawlRef) {

        HttpCrawlReference httpData = (HttpCrawlReference) crawlRef;
        // Mark original URL as processed
        String originalRef = httpData.getOriginalReference();
        String finalRef = httpData.getReference();
        if (StringUtils.isNotBlank(originalRef)
                && ObjectUtils.notEqual(originalRef, finalRef)) {
            HttpCrawlReference originalData = (HttpCrawlReference) httpData.clone();
            originalData.setReference(originalRef);
            originalData.setOriginalReference(null);
            getCrawlReferenceService().processed(originalData);
        }
    }

    @Override
    protected void cleanupExecution(JobStatusUpdater statusUpdater,
            JobSuite suite) {
        try {
//            if (sitemapResolver != null) {
//                sitemapResolver.stop();
//            }
        } catch (Exception e) {
            LOG.error("Could not stop sitemap store.");
        }
//        closeHttpClient();
    }

    private void metadataAddString(
            HttpMetadata metadata, String key, String value) {
        if (value != null) {
            metadata.add(key, value);
        }
    }

    @Override
    protected void resumeExecution(JobStatusUpdater statusUpdater,
            JobSuite suite) {
        //TODO get rid of this method?
        throw new UnsupportedOperationException("resumeExecution not supported???");

    }

//    // Wraps redirection strategy to consider URLs as new documents to
//    // queue for processing.
//    private void initializeRedirectionStrategy() {
//        try {
//            Object chain = FieldUtils.readField(httpClient, "execChain", true);
//            Object redir = FieldUtils.readField(
//                    chain, "redirectStrategy", true);
//            if (redir instanceof RedirectStrategy) {
//                RedirectStrategy originalStrategy = (RedirectStrategy) redir;
//                RedirectStrategyWrapper strategyWrapper =
//                        new RedirectStrategyWrapper(originalStrategy,
//                                getCrawlerConfig().getRedirectURLProvider());
//                FieldUtils.writeField(
//                        chain, "redirectStrategy", strategyWrapper, true);
//            } else {
//                LOG.warn("Could not wrap RedirectStrategy to properly handle"
//                        + "redirects.");
//            }
//        } catch (Exception e) {
//            LOG.warn("\"maxConnectionInactiveTime\" could not be set since "
//                    + "internal connection manager does not support it.");
//        }
//    }
}
