/*
 * Copyright 2012-2014 eBay Software Foundation and selendroid committers.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.selendroid.server.builder;

import io.selendroid.common.JavaSdk;
import io.selendroid.common.exceptions.SelendroidException;
import io.selendroid.common.android.AndroidApp;
import io.selendroid.common.android.AndroidSdk;
import io.selendroid.common.android.DefaultAndroidApp;
import io.selendroid.common.exceptions.AndroidSdkException;
import io.selendroid.common.exceptions.ShellCommandException;
import io.selendroid.common.ShellCommand;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SelendroidServerBuilder {
  // Hardcoded version for now, we shouldn't need getJarVersionNumber
  private static final String PREBUILD_SELENDROID_SERVER_PATH =
    "/prebuild/selendroid-server-" + getJarVersionNumber() + ".apk";
  private static final String DEFAULT_MANIFEST_TEMPLATE =
    "/AndroidManifestTemplate.xml";
  private static final String DEFAULT_SIG_ALG = "MD5withRSA";
  private static final Logger log =
    Logger.getLogger(SelendroidServerBuilder.class.getName());
  private static final String TARGET_PACKAGE_TEMPLATE = "${TARGET_PACKAGE}";

  private AndroidApp aut;
  private boolean deleteTempFiles = true;
  private File outputFile;
  private String keystorePath;
  private String keystoreAlias = "androiddebugkey";
  private String keystorePassword = "android";

  // These are only customizable for testing
  private String prebuildServerPath=  PREBUILD_SELENDROID_SERVER_PATH;
  private String manifestTemplatePath = DEFAULT_MANIFEST_TEMPLATE;

  public AndroidApp build()
    throws AndroidSdkException, FileNotFoundException, IOException {
    if (aut == null) {
      throw new IllegalStateException("Application under test required");
    }

    File customizedApk = createSelendroidServerApk();

    if (outputFile == null) {
      outputFile = createTempFile(
        String.format(
          "instrumentation-%s",
          aut.getBasePackage()),
        ".apk");
    }

    return signApk(customizedApk, outputFile);
  }

  public AndroidApp resignApk(
    File apk) throws ShellCommandException, AndroidSdkException, IOException {
    AndroidApp app = new DefaultAndroidApp(apk);

    // Delete existing certificates
    deleteFileFromAppSilently(app, "META-INF/MANIFEST.MF");
    deleteFileFromAppSilently(app, "META-INF/CERT.RSA");
    deleteFileFromAppSilently(app, "META-INF/CERT.SF");
    deleteFileFromAppSilently(app, "META-INF/ANDROIDD.SF");
    deleteFileFromAppSilently(app, "META-INF/ANDROIDD.RSA");
    deleteFileFromAppSilently(app, "META-INF/NDKEYSTO.SF");
    deleteFileFromAppSilently(app, "META-INF/NDKEYSTO.RSA");

    return signApk(apk, createTempFile("resigned-", apk.getName()));
  }


  private void deleteFileFromAppSilently(
    AndroidApp app,
    String filename) throws AndroidSdkException {
    try {
      app.deleteFileFromWithinApk(filename);
    } catch (ShellCommandException e) {
      // no-op
    }
  }

  /*private*/ File createSelendroidServerApk()
    throws IOException, ShellCommandException, AndroidSdkException {
    // Copy sample prebuild APK
    File customizedServer = copyPrebuildServerApk();
    log.info(
      "Creating customized Selendroid-server: " + customizedServer.getAbsolutePath());

    // Clean up APK from pebuild, delete files from Gradle signing
    AndroidApp selendroidServer = new DefaultAndroidApp(customizedServer);
    selendroidServer.deleteFileFromWithinApk("META-INF/CERT.RSA");
    selendroidServer.deleteFileFromWithinApk("META-INF/CERT.SF");
    selendroidServer.deleteFileFromWithinApk("AndroidManifest.xml");

    File manifestApkFile = createTempFile("manifest", ".apk");
    File customizedManifest = createCustomManifest();

    // adding the xml to an empty apk
    ShellCommand.exec(
      (new CommandLine(AndroidSdk.aapt()))
        .addArgument("package")
        .addArgument("-M")
        .addArgument(customizedManifest.getAbsolutePath())
        .addArgument("-I")
        .addArgument(AndroidSdk.androidJar())
        .addArgument("-F")
        .addArgument(manifestApkFile.getAbsolutePath())
        .addArgument("-f"));

    ZipFile manifestApk = new ZipFile(manifestApkFile);
    ZipArchiveEntry binaryManifestXml = manifestApk.getEntry("AndroidManifest.xml");
    File finalSelendroidServerFile = createTempFile("selendroid-server", ".apk");
    ZipArchiveOutputStream finalSelendroidServer =
        new ZipArchiveOutputStream(finalSelendroidServerFile);
    finalSelendroidServer.putArchiveEntry(binaryManifestXml);
    IOUtils.copy(manifestApk.getInputStream(binaryManifestXml), finalSelendroidServer);

    ZipFile selendroidPrebuildApk = new ZipFile(selendroidServer.getAbsolutePath());
    Enumeration<ZipArchiveEntry> entries = selendroidPrebuildApk.getEntries();
    while (entries.hasMoreElements()) {
      ZipArchiveEntry dd = entries.nextElement();
      finalSelendroidServer.putArchiveEntry(dd);
      IOUtils.copy(selendroidPrebuildApk.getInputStream(dd), finalSelendroidServer);
    }

    finalSelendroidServer.closeArchiveEntry();
    finalSelendroidServer.close();
    manifestApk.close();
    log.info("file: " + finalSelendroidServerFile.getAbsolutePath());

    return finalSelendroidServerFile;
  }

  // TODO: Use a library or something for building the XML file
  private File createCustomManifest() throws IOException, AndroidSdkException {
    File customizedManifest = createTempFile("AndroidManifest" , ".xml");
    log.info(
      String.format(
        "Adding target package '%s' to %s",
        aut.getBasePackage(),
        customizedManifest.getAbsolutePath()
      ));

    InputStream inputStream = getResourceAsStream(manifestTemplatePath);
    OutputStream outputStream = new FileOutputStream(customizedManifest);
    String content = IOUtils.toString(inputStream, Charset.defaultCharset().displayName());

    try {
      content = content.replace(TARGET_PACKAGE_TEMPLATE, aut.getBasePackage());
      log.info("Final Manifest File:\n" + content);
      IOUtils.write(content, outputStream, Charset.defaultCharset().displayName());
    } finally {
      IOUtils.closeQuietly(inputStream);
      IOUtils.closeQuietly(outputStream);
    }

    return customizedManifest;
  }

  private final File copyPrebuildServerApk() throws IOException {
    File customizedServer = createTempFile("selendroid-server", ".apk");
    InputStream is = getResourceAsStream(prebuildServerPath);
    IOUtils.copy(is, new FileOutputStream(customizedServer));
    IOUtils.closeQuietly(is);

    return customizedServer;
  }

  private InputStream getResourceAsStream(String resource) {
    InputStream is = getClass().getClassLoader().getResourceAsStream(resource);

    if (is == null) {
      String pwd = ShellCommand.exec(new CommandLine("pwd"));
      throw new SelendroidException("The resource '" + resource + "' was not found. " + pwd);
    }

    return is;
  }

  /* private */AndroidApp signApk(
    File customApk,
    File output) throws AndroidSdkException {
    log.info(String.format("Signing APK %s", customApk.toString()));

    ShellCommand.exec(
      (new CommandLine(JavaSdk.jarsigner()))
        .addArgument("-sigalg")
        .addArgument(getSigAlg())
        .addArgument("-digestalg")
        .addArgument("SHA1")
        .addArgument("-signedjar")
        .addArgument(output.getAbsolutePath())
        .addArgument("-storepass")
        .addArgument(keystorePassword)
        .addArgument("-keystore")
        .addArgument(keystorePath)
        .addArgument(customApk.getAbsolutePath())
        .addArgument(keystoreAlias));

    log.info(
      String.format("Done signing APK %s", customApk.toString()));

    return new DefaultAndroidApp(output);
  }

  private String getSigAlg() {
    if (keystorePath == null) {
      return DEFAULT_SIG_ALG;
    }

    try {
      FileInputStream in = new FileInputStream(keystorePath);
      KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
      char[] keystorePasswordCharArray = keystorePassword.toCharArray();

      keystore.load(in, keystorePasswordCharArray);
      return ((X509Certificate) keystore.getCertificate(keystoreAlias))
        .getSigAlgName();
    } catch (Exception e) {
      log.log(Level.WARNING, String.format(
          "Error getting signature algorithm for jarsigner. Defaulting to %s. Reason: %s",
          DEFAULT_SIG_ALG,
          e.getMessage()));
    }

    return DEFAULT_SIG_ALG;
  }

  public static String getJarVersionNumber() {
    if (true) {
      return "0.18.1";
    }
    return SelendroidServerBuilder
      .class
      .getPackage()
      .getImplementationVersion();
  }

  public SelendroidServerBuilder withApplicationUnderTest(AndroidApp aut) {
    this.aut = aut;
    return this;
  }

  public SelendroidServerBuilder withKeystorePath(String keystorePath) {
    this.keystorePath = keystorePath;
    return this;
  }

  public SelendroidServerBuilder withKeystoreAlias(String keystoreAlias) {
    this.keystoreAlias = keystoreAlias;
    return this;
  }

  public SelendroidServerBuilder withKeystorePassword(String keystorePassword) {
    this.keystorePassword = keystorePassword;
    return this;
  }

  public SelendroidServerBuilder withDeleteTempFiles(boolean deleteTempFiles) {
    this.deleteTempFiles = deleteTempFiles;
    return this;
  }

  public SelendroidServerBuilder withOutputFile(File outputFile) {
    this.outputFile = outputFile;
    return this;
  }

  public SelendroidServerBuilder withManifestTemplatePath(
    String manifestTemplatePath) {
    this.manifestTemplatePath = manifestTemplatePath;
    return this;
  }

  public SelendroidServerBuilder withPrebuildServerPath(
    String prebuildServerPath) {
    this.prebuildServerPath = prebuildServerPath;
    return this;
  }

  private File createTempFile(String name, String suffix) throws IOException {
    File tempFile = File.createTempFile(name, suffix);

    if (deleteTempFiles) {
      tempFile.deleteOnExit();
    }

    return tempFile;
  }
}
