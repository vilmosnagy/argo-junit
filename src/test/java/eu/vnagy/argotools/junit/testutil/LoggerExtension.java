package eu.vnagy.argotools.junit.testutil;

/*-
 * #%L
 * Argo JUnit
 * %%
 * Copyright (C) 2026 Vilmos Szabó-Nagy
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

import java.util.List;

/** JUnit 5 extension that captures all log events for the duration of each test. */
public class LoggerExtension implements BeforeEachCallback, AfterEachCallback {

    private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @Override
    public void beforeEach(ExtensionContext context) {
        logger.addAppender(listAppender);
        listAppender.start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        listAppender.stop();
        listAppender.list.clear();
        logger.detachAppender(listAppender);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(listAppender.list);
    }

    public List<String> formattedMessages() {
        return listAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
