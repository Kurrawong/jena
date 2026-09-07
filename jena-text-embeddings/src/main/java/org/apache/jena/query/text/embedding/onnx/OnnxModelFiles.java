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

package org.apache.jena.query.text.embedding.onnx;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

import org.apache.jena.query.text.embedding.EmbeddingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the four files an ONNX sentence-embedding model needs, downloading them from
 * HuggingFace if they are not already on disk.
 * <p>
 * A pre-populated directory is the intended production shape — bake the model into a
 * derived image — so a complete directory is used as-is and no network call is made. The
 * download path exists for local development.
 */
final class OnnxModelFiles {

    private static final Logger log = LoggerFactory.getLogger(OnnxModelFiles.class);

    static final String ONNX_FILE = "model.onnx";

    /**
     * {@code model.onnx} lives under {@code onnx/} in the repository but is flattened
     * beside the tokenizer locally, because Jlama's tokenizer wants one directory.
     */
    private static final List<String[]> FILES = List.of(
        new String[] { "onnx/model.onnx",     ONNX_FILE },
        new String[] { "tokenizer.json",      "tokenizer.json" },
        new String[] { "tokenizer_config.json", "tokenizer_config.json" },
        new String[] { "config.json",         "config.json" });

    private OnnxModelFiles() {}

    static File resolve(String modelPath, String modelId) {
        File root = new File(modelPath);
        File dir = new File(root, modelId.replace('/', '_') + "-onnx");
        if (isComplete(dir)) {
            return dir;
        }
        download(dir, modelId);
        return dir;
    }

    private static boolean isComplete(File dir) {
        if (!dir.isDirectory()) return false;
        for (String[] f : FILES) {
            File local = new File(dir, f[1]);
            // A zero-length file is what a half-finished download leaves behind; treating
            // it as present would fail later, in ONNX Runtime, with a far worse message.
            if (!local.isFile() || local.length() == 0) return false;
        }
        return true;
    }

    private static void download(File dir, String modelId) {
        log.info("ONNX model '{}' not present under {}; downloading from HuggingFace", modelId, dir);
        try {
            Files.createDirectories(dir.toPath());
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            for (String[] f : FILES) {
                File target = new File(dir, f[1]);
                if (target.isFile() && target.length() > 0) continue;
                String url = "https://huggingface.co/" + modelId + "/resolve/main/" + f[0];
                fetch(client, url, target);
            }
        } catch (EmbeddingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EmbeddingException("Could not download ONNX model '" + modelId + "' into "
                + dir + ". Either pre-populate that directory or allow network access to "
                + "huggingface.co.", ex);
        }
    }

    private static void fetch(HttpClient client, String url, File target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response =
            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new EmbeddingException("HTTP " + response.statusCode() + " fetching " + url
                + ". Not every model publishes an ONNX export; check that "
                + "onnx/model.onnx exists in the repository.");
        }
        // Download beside the target and move into place, so an interrupted run cannot
        // leave a truncated file that looks complete on the next startup.
        Path tmp = Path.of(target.getAbsolutePath() + ".part");
        try (InputStream in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        log.info("  fetched {} ({} bytes)", target.getName(), target.length());
    }
}
