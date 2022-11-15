/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.tooling.support.install.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.jar.JarInputStream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer2;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

import static org.mockito.AdditionalAnswers.answer;

class InstallServletTest {

    private InstallServlet servlet;
    private BundleContext bundleContext;
    private ByteArrayOutputStream output; // data of installed bundle's JAR

    @BeforeEach
    void setUp() throws BundleException {
        output = new ByteArrayOutputStream();
        bundleContext = Mockito.mock(BundleContext.class);
        Mockito.when(bundleContext.getBundles()).thenReturn(new Bundle[0]);
        Mockito.when(bundleContext.installBundle(Mockito.anyString(), Mockito.any(InputStream.class)))
            .then(answer(new Answer2<Bundle, String, InputStream>() {
                @Override
                public Bundle answer(String location, InputStream inputStream) throws Throwable {
                    IOUtils.copy(inputStream, output);
                    return Mockito.mock(Bundle.class);
                }
            }));
        servlet = new InstallServlet(bundleContext);
    }

    @Test
    void testInstallJar() throws IOException, BundleException {
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/org.apache.sling.commons.messaging-1.0.0.jar"))) {
            servlet.installBundleFromJar(input, false);
        }
        Mockito.verify(bundleContext).installBundle(Mockito.eq("inputstream:org.apache.sling.commons.messaging-1.0.0.jar"), Mockito.any(InputStream.class));
        assertBundle(output.toByteArray(), 17, "org.apache.sling.commons.messaging");
    }

    @Test
    void testInstallDirectory() throws IOException, URISyntaxException, BundleException {
        Path sourceDir = Paths.get(getClass().getResource("/exploded-bundle1").toURI());
        servlet.installBundleFromDirectory(sourceDir, false);
        Mockito.verify(bundleContext).installBundle(Mockito.eq(sourceDir.toString()), Mockito.any(InputStream.class));
        assertBundle(output.toByteArray(), 5, "test-bundle1");
    }

    static void assertBundle(byte[] data, int expectedNumEntries, String expectedBSN) throws IOException {
        int numEntries = 1;
        try (JarInputStream jarInputStream = new JarInputStream(new ByteArrayInputStream(data))) {
            assertEquals(expectedBSN, jarInputStream.getManifest().getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME));
            while(jarInputStream.getNextEntry() != null) {
                numEntries++;
            }
        }
        assertEquals(expectedNumEntries, numEntries);
    }
}
