/*
 * Copyright (c) 2016, Groupon, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * Neither the name of GROUPON nor the names of its contributors may be
 * used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.groupon.lex.metrics;

import com.groupon.lex.metrics.api.endpoints.ListMetrics;
import com.groupon.lex.metrics.httpd.EndpointRegistration;
import com.groupon.lex.metrics.misc.MonitorMonitor;
import com.groupon.lex.metrics.timeseries.Alert;
import com.groupon.lex.metrics.timeseries.ExpressionLookBack;
import com.groupon.lex.metrics.timeseries.MutableTimeSeriesValue;
import com.groupon.lex.metrics.timeseries.TimeSeriesCollection;
import com.groupon.lex.metrics.timeseries.TimeSeriesTransformer;
import com.groupon.lex.metrics.timeseries.TimeSeriesValue;
import com.groupon.lex.metrics.timeseries.expression.Context;
import com.groupon.lex.metrics.timeseries.expression.MutableContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import static java.util.Objects.requireNonNull;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 *
 * @author ariane
 */
public abstract class MetricRegistryInstance implements MetricRegistry, AutoCloseable {
    private static final Logger logger = Logger.getLogger(MetricRegistryInstance.class.getName());
    private final Collection<GroupGenerator> generators_ = new ArrayList<>();
    private long failed_collections_ = 0;
    private final boolean has_config_;
    private Optional<Duration> scrape_duration_ = Optional.empty();
    private Optional<Duration> rule_eval_duration_ = Optional.empty();
    private Optional<Duration> processor_duration_ = Optional.empty();
    private final EndpointRegistration api_;
    private final Collection<TimeSeriesTransformer> decorators_ = new ArrayList<>();
    private Supplier<DateTime> now_;
    private final ListMetrics list_metrics_;

    protected MetricRegistryInstance(@NonNull Supplier<DateTime> now, boolean has_config, @NonNull EndpointRegistration api) {
        api_ = api;
        has_config_ = has_config;
        decorators_.add(new MonitorMonitor(this));
        now_ = requireNonNull(now);
        list_metrics_ = new ListMetrics();
        getApi().addEndpoint("/monsoon/metrics", list_metrics_);
    }

    protected MetricRegistryInstance(boolean has_config, EndpointRegistration api) {
        this(TimeSeriesCollection::now, has_config, api);
    }

    @Override
    public EndpointRegistration getApi() { return api_; }

    public synchronized GroupGenerator add(GroupGenerator g) {
        generators_.add(g);
        return g;
    }

    public synchronized void remove(GroupGenerator g) {
        generators_.remove(g);
    }

    /**
     * Retrieve the number of collectors that encountered a failure during the last call to streamGroups().
     * @return The number of collectors that failed.
     */
    public long getFailedCollections() { return failed_collections_; }
    /**
     * Retrieve timing for scrape.
     * @return The duration it took to complete all scrapes.
     */
    public Optional<Duration> getScrapeDuration() { return scrape_duration_; }
    /**
     * Retrieve timing for rule evaluation.
     * @return The duration it took to evaluate all rules.
     */
    public Optional<Duration> getRuleEvalDuration() { return rule_eval_duration_; }
    /**
     * Retrieve timing for processor to handle the data.
     * @return The duration it took for the processor, to push all the data out.
     */
    public Optional<Duration> getProcessorDuration() { return processor_duration_; }
    /**
     * Update the processor duration.
     * @param duration The time spent in the processor.
     */
    public void updateProcessorDuration(Duration duration) { processor_duration_ = Optional.of(duration); }

    private Stream<TimeSeriesValue> streamGroups() {
        return streamGroups(now());
    }

    private synchronized Stream<TimeSeriesValue> streamGroups(DateTime now) {
        final long t0 = System.nanoTime();

        List<GroupGenerator.GroupCollection> collections = generators_.parallelStream()
                .map(GroupGenerator::getGroups)
                .collect(Collectors.toList());

        /* Count collection failures. */
        failed_collections_ = collections.stream()
                .filter((result) -> !result.isSuccessful())
                .count();
        /* Measure end time of collections. */
        final long t_collections = System.nanoTime();
        scrape_duration_ = Optional.of(Duration.millis(TimeUnit.NANOSECONDS.toMillis(t_collections - t0)));

        Stream<TimeSeriesValue> groups = collections.stream()
                .map(GroupGenerator.GroupCollection::getGroups)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(MetricGroup::getName, Function.identity(), (x, y) -> y))  // Resolve group-name conflict, such that latest metric wins.
                .values()
                .stream()
                .map((mg) -> new MutableTimeSeriesValue(now, mg.getName(), Arrays.stream(mg.getMetrics()), Metric::getName, Metric::getValue));
        return groups;
    }

    @Override
    public synchronized GroupName[] getGroupNames() {
        return streamGroups().map(TimeSeriesValue::getGroup).toArray(GroupName[]::new);
    }

    public synchronized void decorate(TimeSeriesTransformer decorator) {
        decorators_.add(Objects.requireNonNull(decorator));
    }

    /**
     * Closes the MetricRegistryInstance.
     */
    @Override
    public void close() {
        generators_.forEach((g) -> {
                    try {
                        g.close();
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "failed to close group generator " + g, e);
                    }
                });
        if (api_ instanceof AutoCloseable) {
            try {
                ((AutoCloseable)api_).close();
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "unable to close API " + api_.getClass(), ex);
            }
        }
    }

    @Override
    public boolean hasConfig() { return has_config_; }
    public DateTime now() { return requireNonNull(now_.get()); }

    /**
     * Apply all timeseries decorators.
     * @param ctx Input timeseries.
     */
    protected void apply_rules_and_decorators_(Context ctx) {
        decorators_.forEach(tf -> tf.transform(ctx));
    }

    public ExpressionLookBack getDecoratorLookBack() {
        return ExpressionLookBack.EMPTY.andThen(decorators_.stream().map(TimeSeriesTransformer::getLookBack));
    }

    public static interface CollectionContext {
        public Consumer<Alert> alertManager();
        public MutableTimeSeriesCollectionPair tsdata();
        public void commit();
    }

    protected abstract CollectionContext beginCollection(DateTime now);

    /**
     * Run an update cycle.
     * An update cycle consists of:
     * - gathering raw metrics
     * - creating a new, minimal context
     * - applying decorators against the current and previous values
     * - storing the collection values as the most recent capture
     */
    public TimeSeriesCollection updateCollection() {
        // Scrape metrics from all collectors.
        final DateTime now = now();
        final CollectionContext cctx = beginCollection(now);
        final MutableTimeSeriesCollectionPair tsdata = cctx.tsdata();
        streamGroups(now).forEach(tsdata.getCurrentCollection()::add);

        // Build a rule evaluation context.
        final Context ctx = new MutableContext(tsdata, cctx.alertManager());

        // Apply rules.
        final long t0 = System.nanoTime();
        apply_rules_and_decorators_(ctx);
        final long t_rule_eval = System.nanoTime();
        rule_eval_duration_ = Optional.of(Duration.millis(TimeUnit.NANOSECONDS.toMillis(t_rule_eval - t0)));

        // Publish new set of metrics.
        list_metrics_.update(tsdata.getCurrentCollection());

        // Inform derived class that we are done.
        cctx.commit();

        // Return tsdata.
        return tsdata.getCurrentCollection();
    }
}
