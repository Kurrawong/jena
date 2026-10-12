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

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.text.cmd.textindexdump;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code textindexdump} on a SHACL index prints fields kept in doc values only, as it
 * prints stored fields.
 */
public class TestTextIndexDump {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static String config(String directory) {
        return "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <" + FIELD + "> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + "text:TextIndexShacl rdfs:subClassOf text:TextIndex .\n"
            + "field:title   idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
            + "field:code    idx:fieldName \"code\" ; idx:fieldType idx:KeywordField ; idx:sortable true .\n"
            + "field:tags    idx:fieldName \"tags\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;"
            + " idx:multiValued true .\n"
            + "field:idValue idx:fieldName \"idValue\" ; idx:fieldType idx:KeywordField ; idx:sortable true .\n"
            + "ex:DocShape\n"
            + "    sh:targetClass ex:Doc ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    sh:property [ idx:field field:code ; sh:path ex:code ] ;\n"
            + "    sh:property [ idx:field field:tags ; sh:path ex:tag ] ;\n"
            + "    idx:nested [\n"
            + "        idx:joinPath ex:identifier ;\n"
            + "        idx:property [ idx:field field:idValue ; sh:path ex:idValue ] ;\n"
            + "    ] .\n"
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory " + directory + " ;\n"
            + "    text:storeValues true ;\n"
            + "    text:shapes ( ex:DocShape ) .\n";
    }

    @Test
    public void dumpPrintsFieldsKeptInDocValues(@TempDir Path dir) throws Exception {
        Path indexDir = dir.resolve("index");
        String ttl = config("<" + IRILib.filenameToIRI(indexDir.toAbsolutePath().toString()) + ">");
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(ttl), null, "TTL");
        Dataset dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        try {
            Txn.executeWrite(dataset, () -> {
                Model m = dataset.getDefaultModel();
                Resource e1 = m.createResource(NS + "e1");
                e1.addProperty(RDF.type, m.createResource(NS + "Doc"));
                e1.addProperty(m.createProperty(NS + "title"), "alpha");
                e1.addProperty(m.createProperty(NS + "code"), "C1");
                e1.addProperty(m.createProperty(NS + "tag"), "red");
                e1.addProperty(m.createProperty(NS + "tag"), "blue");
                Resource ident = m.createResource();
                ident.addProperty(m.createProperty(NS + "idValue"), "10/1");
                e1.addProperty(m.createProperty(NS + "identifier"), ident);
            });
        } finally {
            dataset.close();
        }
        Path configFile = dir.resolve("config.ttl");
        Files.writeString(configFile, ttl);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = System.out;
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            textindexdump.main(configFile.toString());
        } finally {
            System.setOut(out);
        }
        List<String> lines = Arrays.asList(bytes.toString(StandardCharsets.UTF_8).split("\\R"));

        assertTrue(lines.contains("  title = alpha"), "a stored field: " + lines);
        assertEquals(1, lines.stream().filter("  code = C1"::equals).count(), "code once: " + lines);
        assertTrue(lines.contains("  tags = blue"), "multi-valued: " + lines);
        assertTrue(lines.contains("  tags = red"), "multi-valued: " + lines);
        assertTrue(lines.contains("  idValue = 10/1"), "nested: " + lines);
    }
}
