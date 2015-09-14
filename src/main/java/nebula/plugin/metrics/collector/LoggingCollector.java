/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package nebula.plugin.metrics.collector;

import nebula.plugin.metrics.MetricsPluginExtension;
import nebula.plugin.metrics.dispatcher.MetricsDispatcher;

import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Service;
import org.gradle.api.logging.LogLevel;
import org.gradle.logging.internal.LogEvent;
import org.gradle.logging.internal.OutputEvent;
import org.gradle.logging.internal.OutputEventListener;
import org.gradle.logging.internal.slf4j.OutputEventListenerBackedLoggerContext;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Collector that intercepts logging events.
 *
 * @author Danny Thomas
 */
public class LoggingCollector {
    /**
     * Configure a logback filter to capture all root logging events.
     * <p>
     * Avoids having to depend on a particular Gradle logging level being set. Gradle's logging is such that
     * encoders/layouts/etc aren't an option and LogbackLoggingConfigurer.doConfigure() adds a TurboFilter which
     * prevents us getting at those events, so we re-wire the filters so ours comes first.
     *
     * @param dispatcherSupplier the dispatcher supplier
     * @param extension          the extension
     */
    public static void configureCollection(final Supplier<MetricsDispatcher> dispatcherSupplier, final MetricsPluginExtension extension) {
        checkNotNull(dispatcherSupplier);
        checkNotNull(extension);
        final BlockingQueue<LogEvent> logEvents = new LinkedBlockingQueue<>();
        OutputEventListenerBackedLoggerContext context = (OutputEventListenerBackedLoggerContext) LoggerFactory.getILoggerFactory();
        context.setLevel(LogLevel.DEBUG);
        OutputEventListener originalListener = context.getOutputEventListener();
        OutputEventListener listener = new WrappedOutputEventListener(originalListener) {
            @Override
            public void onOutput(OutputEvent outputEvent) {
                if (outputEvent instanceof LogEvent && outputEvent.getLogLevel().compareTo(extension.getLogLevel()) >= 0 /* || MARKER.equals(marker) */) {
                    LogEvent logEvent = (LogEvent) outputEvent;
                    MetricsDispatcher dispatcher = dispatcherSupplier.get();
                    if (dispatcher.state() == Service.State.NEW || dispatcher.state() == Service.State.STARTING) {
                        logEvents.add(logEvent);
                    } else {
                        if (!logEvents.isEmpty()) {
                            List<LogEvent> drainedEvents = Lists.newArrayListWithCapacity(logEvents.size());
                            logEvents.drainTo(drainedEvents);
                            dispatcher.logEvents(drainedEvents);
                        }
                        dispatcher.logEvent(logEvent);
                    }
                }
                super.onOutput(outputEvent);
            }
        };
        context.setOutputEventListener(listener);
    }

    public static void reset() {
        OutputEventListenerBackedLoggerContext context = (OutputEventListenerBackedLoggerContext) LoggerFactory.getILoggerFactory();
        WrappedOutputEventListener listener = (WrappedOutputEventListener) context.getOutputEventListener();
        context.setOutputEventListener(listener.unwrap());
    }

    private static class WrappedOutputEventListener implements OutputEventListener {
        private final OutputEventListener listener;

        public WrappedOutputEventListener(OutputEventListener listener) {
            this.listener = checkNotNull(listener);
        }

        @Override
        public void onOutput(OutputEvent outputEvent) {
            listener.onOutput(outputEvent);
        }

        public OutputEventListener unwrap() {
            return listener;
        }
    }
}