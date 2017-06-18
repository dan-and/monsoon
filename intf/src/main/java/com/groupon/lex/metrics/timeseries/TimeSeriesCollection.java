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
package com.groupon.lex.metrics.timeseries;

import com.groupon.lex.metrics.GroupName;
import com.groupon.lex.metrics.SimpleGroupPath;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/**
 *
 * @author ariane
 */
public interface TimeSeriesCollection extends Comparable<TimeSeriesCollection> {
    public static DateTime now() {
        return DateTime.now(DateTimeZone.UTC);
    }

    public DateTime getTimestamp();

    public boolean isEmpty();

    public Set<GroupName> getGroups(Predicate<? super GroupName> filter);

    public Set<SimpleGroupPath> getGroupPaths(Predicate<? super SimpleGroupPath> filter);

    public Collection<TimeSeriesValue> getTSValues();

    public TimeSeriesValueSet getTSValue(SimpleGroupPath name);

    public Optional<TimeSeriesValue> get(GroupName name);

    public default TimeSeriesValueSet get(Predicate<? super SimpleGroupPath> pathFilter, Predicate<? super GroupName> groupFilter) {
        return new TimeSeriesValueSet(getGroups(name -> pathFilter.test(name.getPath()) && groupFilter.test(name)).stream()
                .map(this::get)
                .filter(Optional::isPresent)
                .map(Optional::get));
    }

    public default Optional<TimeSeriesValueSet> getTSDeltaByName(GroupName name) {
        return get(name)
                .map(Stream::of)
                .map(TimeSeriesValueSet::new);
    }

    @Override
    public default int compareTo(TimeSeriesCollection o) {
        return getTimestamp().compareTo(o.getTimestamp());
    }
}
