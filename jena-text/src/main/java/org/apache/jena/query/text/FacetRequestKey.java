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

import java.util.Objects;

final class FacetRequestKey {
    private final String canonicalRequest;
    private final int maxValues;
    private final int minCount;

    private FacetRequestKey(String canonicalRequest, int maxValues, int minCount) {
        this.canonicalRequest = canonicalRequest;
        this.maxValues = maxValues;
        this.minCount = minCount;
    }

    static FacetRequestKey of(FacetRequest request, int maxValues, int minCount) {
        return new FacetRequestKey(request.toCanonical(), maxValues, minCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FacetRequestKey that)) return false;
        return maxValues == that.maxValues
            && minCount == that.minCount
            && canonicalRequest.equals(that.canonicalRequest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonicalRequest, maxValues, minCount);
    }
}
