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

package org.apache.jena.query.text.assembler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.stream.Stream;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code idx:PeriodField} configuration: what it accepts, and every shape it cannot honour,
 * rejected at startup with a message naming the problem.
 */
public class TestPeriodFieldConfig {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String PREFIXES = """
        @prefix idx:   <urn:jena:lucene:index#> .
        @prefix field: <urn:jena:lucene:field#> .
        @prefix sh:    <http://www.w3.org/ns/shacl#> .
        @prefix time:  <http://www.w3.org/2006/time#> .
        @prefix ex:    <http://example.org/> .
        field:title idx:fieldName "title" ; idx:fieldType idx:TextField .
        """;

    private static final String PERIOD_FIELD =
        "field:validity idx:fieldName \"validity\" ; idx:fieldType idx:PeriodField";

    private static final String PERIOD_PATHS =
        "idx:periodStart ( time:hasBeginning time:inXSDDate ) ; idx:periodEnd ( time:hasEnd time:inXSDDate )";

    private static String shape(String fieldExtras, String occurrence) {
        return PREFIXES + PERIOD_FIELD + fieldExtras + " .\n"
            + "ex:Shape sh:targetClass ex:Thing ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    " + occurrence + " .\n";
    }

    private static String periodOccurrence(String paths) {
        return "sh:property [ idx:field field:validity ; sh:path ex:validity ; " + paths + " ]";
    }

    @Test
    public void aPeriodFieldIsUnstoredByDefault() {
        ShaclIndexMapping mapping = parse(shape("", periodOccurrence(PERIOD_PATHS)));
        ShaclIndexMapping.FieldDef validity = mapping.findField("urn:jena:lucene:field#validity");
        assertEquals(ShaclIndexMapping.FieldType.PERIOD, validity.getFieldType());
        assertFalse(validity.isStored(), "a period has no single literal to return");
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
            Arguments.of("no idx:periodStart", shape("", periodOccurrence(
                "idx:periodEnd ( time:hasEnd time:inXSDDate )")), "idx:periodStart and idx:periodEnd"),
            Arguments.of("no idx:periodEnd", shape("", periodOccurrence(
                "idx:periodStart ( time:hasBeginning time:inXSDDate )")), "idx:periodStart and idx:periodEnd"),
            Arguments.of("period paths on another field type", PREFIXES
                + "ex:Shape sh:targetClass ex:Thing ;\n"
                + "    sh:property [ idx:field field:title ; sh:path ex:title ; " + PERIOD_PATHS + " ] .\n",
                "only on a PeriodField"),
            Arguments.of("period paths on the canonical field", shape(" ; " + PERIOD_PATHS,
                periodOccurrence(PERIOD_PATHS)), "must not carry occurrence properties"),
            Arguments.of("period paths on a step", PREFIXES + PERIOD_FIELD + " .\n"
                + "ex:Shape sh:targetClass ex:Thing ;\n"
                + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
                + "    sh:property [ sh:path ex:term ; " + PERIOD_PATHS + " ;\n"
                + "        sh:node [ sh:property [ idx:field field:validity ; sh:path ex:validity ; "
                + PERIOD_PATHS + " ] ] ] .\n",
                "A step takes only sh:path and sh:node"),
            Arguments.of("stored", shape(" ; idx:stored true", periodOccurrence(PERIOD_PATHS)), "idx:stored"),
            Arguments.of("facetable", shape(" ; idx:facetable true", periodOccurrence(PERIOD_PATHS)), "idx:facetable"),
            Arguments.of("sortable", shape(" ; idx:sortable true", periodOccurrence(PERIOD_PATHS)), "idx:sortable"),
            Arguments.of("default search", shape(" ; idx:defaultSearch true", periodOccurrence(PERIOD_PATHS)),
                "idx:defaultSearch"),
            Arguments.of("literal metadata", shape(" ; idx:storeLiteralMetadata true", periodOccurrence(PERIOD_PATHS)),
                "idx:storeLiteralMetadata"),
            Arguments.of("a hierarchy level", PREFIXES + PERIOD_FIELD + " .\n"
                + "ex:Shape sh:targetClass ex:Thing ;\n"
                + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
                + "    " + periodOccurrence(PERIOD_PATHS) + " ;\n"
                + "    idx:facetHierarchy ( field:title field:validity ) .\n",
                "hierarchy"),
            Arguments.of("a path the listener cannot follow", shape("", periodOccurrence(
                "idx:periodStart [ sh:zeroOrMorePath time:hasBeginning ] ; idx:periodEnd ( time:hasEnd time:inXSDDate )")),
                "Unsupported SHACL path")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    public void aShapeAPeriodFieldCannotHonourIsRejected(String what, String config, String messagePart) {
        TextIndexException ex = assertThrows(TextIndexException.class, () -> parse(config));
        assertTrue(ex.getMessage().contains(messagePart), what + ": " + ex.getMessage());
    }

    private static ShaclIndexMapping parse(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        RDFList shapes = model.createList(new Resource[] { model.getResource("http://example.org/Shape") });
        return ShaclIndexAssembler.parseShapes(Assembler.general(), shapes);
    }
}
