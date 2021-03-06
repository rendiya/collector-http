/* Copyright 2013-2019 Norconex Inc.
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
package com.norconex.collector.http.data.store.impl.mongo;

import java.nio.file.Path;

import org.junit.jupiter.api.Disabled;

import com.norconex.collector.core.crawler.CrawlerConfig;
import com.norconex.collector.core.reference.CrawlReferenceService;
import com.norconex.collector.http.data.store.impl.AbstractHttpCrawlDataStoreTest;

@Disabled
public class MongoCrawlDataStoreTest extends AbstractHttpCrawlDataStoreTest {


    @Override
    protected CrawlReferenceService createCrawlDataStore(CrawlerConfig config,
            Path tempFolder, boolean resume) {
        // TODO Auto-generated method stub
        return null;
    }

//    private Fongo fongo;
//
//    @Override
//    @BeforeEach
//    public void setup() throws Exception {
//        fongo = new Fongo("mongo server 1");
//        super.setup();
//    }
//
//    @Test
//    public void testNoNext() throws Exception {
//        assertNull(getCrawlDataStore().nextQueued());
//    }
//
//    @Override
//    protected ICrawlDataStore createCrawlDataStore(
//            CrawlerConfig config, Path tempFolder, boolean resume) {
//        return new MongoCrawlDataStore(resume,
//                fongo.getMongo(), "crawl-test", new MongoCrawlDataSerializer());
//    }
}
