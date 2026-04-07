/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.apache.jena.query.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Canonical facet request model covering flat/hierarchical fields and numeric range facets.
 */
public final class FacetRequest {
    private final List<String> flatFields;
    private final List<RangeFacetSpec> rangeFields;

    public FacetRequest(List<String> flatFields, List<RangeFacetSpec> rangeFields) {
        this.flatFields = List.copyOf(flatFields != null ? flatFields : List.of());
        this.rangeFields = List.copyOf(rangeFields != null ? rangeFields : List.of());
    }

    public static FacetRequest flatOnly(List<String> flatFields) {
        return new FacetRequest(flatFields, List.of());
    }

    public List<String> getFlatFields() {
        return flatFields;
    }

    public List<RangeFacetSpec> getRangeFields() {
        return rangeFields;
    }

    public boolean hasRanges() {
        return !rangeFields.isEmpty();
    }

    public boolean isEmpty() {
        return flatFields.isEmpty() && rangeFields.isEmpty();
    }

    public String toCanonical() {
        List<String> canonicalFlat = new ArrayList<>(flatFields);
        canonicalFlat.sort(String::compareTo);

        List<String> canonicalRanges = rangeFields.stream()
            .sorted(Comparator.comparing(RangeFacetSpec::fieldIri))
            .map(RangeFacetSpec::toCanonical)
            .collect(Collectors.toList());

        return "flat=" + String.join(",", canonicalFlat)
            + "|ranges=" + String.join(",", canonicalRanges);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FacetRequest that)) return false;
        return flatFields.equals(that.flatFields) && rangeFields.equals(that.rangeFields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flatFields, rangeFields);
    }

    public record RangeFacetSpec(String fieldIri, List<String> boundaries) {
        public RangeFacetSpec {
            Objects.requireNonNull(fieldIri, "fieldIri");
            boundaries = boundaries != null
                ? Collections.unmodifiableList(new ArrayList<>(boundaries))
                : List.of();
        }

        public String toCanonical() {
            return fieldIri + "[" + boundaries.stream()
                .map(v -> v != null ? v : "null")
                .collect(Collectors.joining(",")) + "]";
        }
    }
}
