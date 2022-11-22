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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.service.http.whiteboard.propertytypes.HttpWhiteboardContextSelect;
import org.osgi.service.http.whiteboard.propertytypes.HttpWhiteboardServletPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReST endpoint for installing/updating a bundle from a directory
 */
@Component(service = Servlet.class)
@HttpWhiteboardServletPattern("/system/sling/tooling/install")
//choose another servlet context with a higher ranking than the Sling one (https://issues.apache.org/jira/browse/SLING-11677)
@HttpWhiteboardContextSelect("(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)")
public class InstallServlet extends HttpServlet {

    private static final long serialVersionUID = -8820366266126231409L;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final String DIR = "dir";

    private final BundleContext bundleContext;

    @Activate
    public InstallServlet(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        final String dirPath = req.getParameter(DIR);
        final boolean refreshPackages = Boolean.parseBoolean(req.getParameter(dirPath));
        try {
            if (dirPath == null) {
                logger.error("No mandatory dir parameter specified");
                resp.setStatus(500);
                InstallationResult result = new InstallationResult(false, "No mandatory dir parameter specified");
                result.render(resp.getWriter());
                return;
            }
            installBundleFromDirectory(resp, Paths.get(dirPath), refreshPackages);
        } catch (IOException e) {
            throw new ServletException(e);
        }
    }

    private void logAndWriteError(Exception e, HttpServletResponse resp) throws IOException {
        logger.info(e.getMessage(), e);
        resp.setStatus(500);
        new InstallationResult(false, e.getMessage()).render(resp.getWriter());
    }

    private void installBundleFromDirectory(HttpServletResponse resp, final Path dir, boolean refreshPackages) throws IOException {
        try {
            installBundleFromDirectory(dir, refreshPackages);
            InstallationResult result = new InstallationResult(true, null);
            resp.setStatus(200);
            result.render(resp.getWriter());
        } catch (IllegalArgumentException e) {
            logAndWriteError(e, resp);
        }
    }

    /**
     * 
     * @param dir
     * @param refreshPackages
     * @throws IOException
     * @throws IllegalArgumentException if the provided directory does not contain a valid exploded OSGi bundle
     */
    Bundle installBundleFromDirectory(final Path dir, boolean refreshPackages) throws IOException {
        if (Files.isDirectory(dir)) {
            logger.info("Checking dir {} for bundle install", dir);
            final Path manifestFile = dir.resolve(JarFile.MANIFEST_NAME);
            if (Files.exists(dir)) {
                try (InputStream fis = Files.newInputStream(manifestFile)) {
                    final Manifest mf = new Manifest(fis);

                    final String symbolicName = mf.getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME);
                    if (symbolicName != null) {
                        // search bundle
                        Bundle found = getBundle(symbolicName);

                        Path tmpJarFile = Files.createTempFile(dir.getFileName().toString(), "bundle");
                        try {
                            createJar(dir, tmpJarFile, mf);
                            try (InputStream in = Files.newInputStream(tmpJarFile)) {
                                String location = dir.toAbsolutePath().toString();
                                return installOrUpdateBundle(found, in, location, refreshPackages);
                            } catch (final BundleException be) {
                                throw new IllegalArgumentException("Unable to install/update bundle from dir " + dir, be);
                            }
                        } finally {
                            Files.delete(tmpJarFile);
                        }
                    } else {
                        throw new IllegalArgumentException("Manifest in " + dir + " does not have a symbolic name");
                    }
                }
            } else {
                throw new IllegalArgumentException("Dir " + dir + " does not have a manifest");
            }
        } else {
            throw new IllegalArgumentException("Dir " + dir + " does not exist");
        }
    }

    private Bundle installOrUpdateBundle(Bundle bundle, final InputStream in, String location, boolean refreshPackages) throws BundleException {
        if (bundle != null) {
            // update
            bundle.update(in);
        } else {
            // install
            bundle = bundleContext.installBundle(location, in);
            bundle.start();
        }
        if (refreshPackages) {
            refreshBundle(bundle);
        }
        return bundle;
    }
    
    private void refreshBundle(Bundle bundle) {
        FrameworkWiring frameworkWiring = bundleContext.getBundle(Constants.SYSTEM_BUNDLE_ID).adapt(FrameworkWiring.class);
        // take into account added/removed packages for updated bundles and newly satisfied optional package imports
        // for new installed bundles
        frameworkWiring.refreshBundles(Collections.singleton(bundle));
    }

    private Bundle getBundle(final String symbolicName) {
        Bundle found = null;
        for (final Bundle b : this.bundleContext.getBundles()) {
            if (symbolicName.equals(b.getSymbolicName())) {
                found = b;
                break;
            }
        }
        return found;
    }

    private static void createJar(final Path sourceDir, final Path jarFile, final Manifest mf) throws IOException {
        try (JarOutputStream zos = new JarOutputStream(Files.newOutputStream(jarFile))) {
            zos.setLevel(Deflater.NO_COMPRESSION);
            // manifest first
            final ZipEntry anEntry = new ZipEntry(JarFile.MANIFEST_NAME);
            zos.putNextEntry(anEntry);
            mf.write(zos);
            zos.closeEntry();
            zipDir(sourceDir, zos, "");
        }
    }

    public static void zipDir(final Path sourceDir, final ZipOutputStream zos, final String prefix) throws IOException {
        try (Stream<Path> stream = Files.list(sourceDir)) {
            stream.forEach(p -> 
            {
                try {
                    zipFileOrDir(p, zos, prefix);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException ioe) {
            throw ioe.getCause();
        }
    }

    private static void zipFileOrDir(final Path sourceFileOrDir, final ZipOutputStream zos, final String prefix) throws IOException {
        if (Files.isDirectory(sourceFileOrDir)) {
            final String newPrefix = prefix + sourceFileOrDir.getFileName() + "/";
            zos.putNextEntry(new ZipEntry(newPrefix));
            zipDir(sourceFileOrDir, zos, newPrefix);
        } else {
            final String entry = prefix + sourceFileOrDir.getFileName();
            if (!JarFile.MANIFEST_NAME.equals(entry)) {
                try (InputStream fis = Files.newInputStream(sourceFileOrDir)) {
                    final ZipEntry anEntry = new ZipEntry(entry);
                    zos.putNextEntry(anEntry);
                    IOUtils.copy(fis, zos);
                }
            }
        }
    }
}
