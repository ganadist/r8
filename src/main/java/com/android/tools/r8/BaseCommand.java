// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.InternalOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

abstract class BaseCommand {

  private final boolean printHelp;
  private final boolean printVersion;

  private final AndroidApp app;
  private final Path outputPath;
  private final CompilationMode mode;
  private final int minApiLevel;

  BaseCommand(boolean printHelp, boolean printVersion) {
    this.printHelp = printHelp;
    this.printVersion = printVersion;
    // All other fields are initialized with stub/invalid values.
    this.app = null;
    this.outputPath = null;
    this.mode = null;
    this.minApiLevel = 0;
  }

  BaseCommand(AndroidApp app, Path outputPath, CompilationMode mode, int minApiLevel) {
    assert app != null;
    assert mode != null;
    assert minApiLevel > 0;
    this.app = app;
    this.outputPath = outputPath;
    this.mode = mode;
    this.minApiLevel = minApiLevel;
    // Print options are not set.
    printHelp = false;
    printVersion = false;
  }

  public boolean isPrintHelp() {
    return printHelp;
  }

  public boolean isPrintVersion() {
    return printVersion;
  }

  // Internal access to the input resources.
  AndroidApp getInputApp() {
    return app;
  }

  // Internal access to the internal options.
  abstract InternalOptions getInternalOptions();

  public Path getOutputPath() {
    return outputPath;
  }

  public CompilationMode getMode() {
    return mode;
  }

  public int getMinApiLevel() {
    return minApiLevel;
  }

  abstract static class Builder<C extends BaseCommand, B extends Builder<C, B>> {

    private boolean printHelp = false;
    private boolean printVersion = false;
    private final AndroidApp.Builder app;
    private Path outputPath = null;
    private CompilationMode mode;
    private int minApiLevel = Constants.DEFAULT_ANDROID_API;

    protected Builder(CompilationMode mode) {
      this(AndroidApp.builder(), mode);
    }

    // Internal constructor for testing.
    Builder(AndroidApp app, CompilationMode mode) {
      this(AndroidApp.builder(app), mode);
    }

    private Builder(AndroidApp.Builder builder, CompilationMode mode) {
      assert mode != null;
      this.app = builder;
      this.mode = mode;
    }

    abstract B self();

    public abstract C build() throws CompilationException, IOException;

    // Internal accessor for the application resources.
    AndroidApp.Builder getAppBuilder() {
      return app;
    }

    /** Add program file resources. */
    public B addProgramFiles(Path... files) throws IOException {
      app.addProgramFiles(files);
      return self();
    }

    /** Add program file resources. */
    public B addProgramFiles(Collection<Path> files) throws IOException {
      app.addProgramFiles(files);
      return self();
    }

    /** Add classpath file resources. */
    public B addClasspathFiles(Path... files) throws IOException {
      app.addClasspathFiles(files);
      return self();
    }

    /** Add classpath file resources. */
    public B addClasspathFiles(Collection<Path> files) throws IOException {
      app.addClasspathFiles(files);
      return self();
    }

    /** Add library file resources. */
    public B addLibraryFiles(Path... files) throws IOException {
      app.addLibraryFiles(files);
      return self();
    }

    /** Add library file resources. */
    public B addLibraryFiles(List<Path> files) throws IOException {
      app.addLibraryFiles(files);
      return self();
    }

    /** Add Java-bytecode program-data. */
    public B addClassProgramData(byte[]... data) {
      app.addClassProgramData(data);
      return self();
    }

    /** Add Java-bytecode program-data. */
    public B addClassProgramData(Collection<byte[]> data) {
      app.addClassProgramData(data);
      return self();
    }

    /** Add dex program-data. */
    public B addDexProgramData(byte[]... data) {
      app.addDexProgramData(data);
      return self();
    }

    /** Add dex program-data. */
    public B addDexProgramData(Collection<byte[]> data) {
      app.addDexProgramData(data);
      return self();
    }

    /** Get current compilation mode. */
    public CompilationMode getMode() {
      return mode;
    }

    /** Set compilation mode. */
    public B setMode(CompilationMode mode) {
      assert mode != null;
      this.mode = mode;
      return self();
    }

    /** Get the output path. Null if not set. */
    public Path getOutputPath() {
      return outputPath;
    }

    /** Set an output path. Must be an existing directory or a non-existent zip file. */
    public B setOutputPath(Path outputPath) throws CompilationException {
      this.outputPath = FileUtils.validateOutputFile(outputPath);
      return self();
    }

    /** Get the minimum API level (aka SDK version). */
    public int getMinApiLevel() {
      return minApiLevel;
    }

    /** Set the minimum required API level (aka SDK version). */
    public B setMinApiLevel(int minApiLevel) {
      assert minApiLevel > 0;
      this.minApiLevel = minApiLevel;
      return self();
    }

    /** Set the main-dex list file. */
    public B setMainDexListFile(Path file) {
      app.setMainDexListFile(file);
      return self();
    }

    /** True if the print-help flag is enabled. */
    public boolean isPrintHelp() {
      return printHelp;
    }

    /** Set the value of the print-help flag. */
    public B setPrintHelp(boolean printHelp) {
      this.printHelp = printHelp;
      return self();
    }

    /** True if the print-version flag is enabled. */
    public boolean isPrintVersion() {
      return printVersion;
    }

    /** Set the value of the print-version flag. */
    public B setPrintVersion(boolean printVersion) {
      this.printVersion = printVersion;
      return self();
    }
  }
}
