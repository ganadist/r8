// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.CompatProguardCommandBuilder;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wrapper to make it easy to call R8 in compat mode when compiling a dump file.
 *
 * <p>This wrapper will be added to the classpath so it *must* only refer to the public API. See
 * {@code tools/compiledump.py}.
 *
 * <p>It is tempting to have this class share the R8 parser code, but such refactoring would not be
 * valid on past version of the R8 API. Thus there is little else to do than reimplement the parts
 * we want to support for reading dumps.
 */
public class CompileDumpCompatR8 {

  private static final List<String> VALID_OPTIONS =
      Arrays.asList("--classfile", "--compat", "--debug", "--release");

  private static final List<String> VALID_OPTIONS_WITH_OPERAND =
      Arrays.asList(
          "--output",
          "--lib",
          "--classpath",
          "--min-api",
          "--main-dex-rules",
          "--main-dex-list",
          "--main-dex-list-output",
          "--pg-conf",
          "--pg-map-output",
          "--desugared-lib");

  public static void main(String[] args) throws CompilationFailedException {
    boolean isCompatMode = false;
    OutputMode outputMode = OutputMode.DexIndexed;
    Path outputPath = null;
    CompilationMode compilationMode = CompilationMode.RELEASE;
    List<Path> program = new ArrayList<>();
    List<Path> library = new ArrayList<>();
    List<Path> classpath = new ArrayList<>();
    List<Path> config = new ArrayList<>();
    int minApi = 1;
    for (int i = 0; i < args.length; i++) {
      String option = args[i];
      if (VALID_OPTIONS.contains(option)) {
        switch (option) {
          case "--classfile":
            {
              outputMode = OutputMode.ClassFile;
              break;
            }
          case "--compat":
            {
              isCompatMode = true;
              break;
            }
          case "--debug":
            {
              compilationMode = CompilationMode.DEBUG;
              break;
            }
          case "--release":
            {
              compilationMode = CompilationMode.RELEASE;
              break;
            }
          default:
            throw new IllegalArgumentException("Unimplemented option: " + option);
        }
      } else if (VALID_OPTIONS_WITH_OPERAND.contains(option)) {
        String operand = args[++i];
        switch (option) {
          case "--output":
            {
              outputPath = Paths.get(operand);
              break;
            }
          case "--lib":
            {
              library.add(Paths.get(operand));
              break;
            }
          case "--classpath":
            {
              classpath.add(Paths.get(operand));
              break;
            }
          case "--min-api":
            {
              minApi = Integer.parseInt(operand);
              break;
            }
          case "--pg-conf":
            {
              config.add(Paths.get(operand));
              break;
            }
          default:
            throw new IllegalArgumentException("Unimplemented option: " + option);
        }
      } else {
        program.add(Paths.get(option));
      }
    }
    R8.run(
        new CompatProguardCommandBuilder(isCompatMode)
            .addProgramFiles(program)
            .addLibraryFiles(library)
            .addClasspathFiles(classpath)
            .addProguardConfigurationFiles(config)
            .setOutput(outputPath, outputMode)
            .setMode(compilationMode)
            .setMinApiLevel(minApi)
            .build());
  }
}