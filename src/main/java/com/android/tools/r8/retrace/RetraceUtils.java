// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace;

import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.TypeReference;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import java.util.Set;

public class RetraceUtils {

  private static final Set<String> UNKNOWN_SOURCEFILE_NAMES =
      Sets.newHashSet("", "SourceFile", "Unknown", "Unknown Source");

  public static String methodDescriptionFromMethodReference(
      MethodReference methodReference, boolean verbose) {
    if (!verbose || methodReference.isUnknown()) {
      return methodReference.getHolderClass().getTypeName() + "." + methodReference.getMethodName();
    }
    StringBuilder sb = new StringBuilder();
    sb.append(
        methodReference.getReturnType() == null
            ? "void"
            : methodReference.getReturnType().getTypeName());
    sb.append(" ");
    sb.append(methodReference.getHolderClass().getTypeName());
    sb.append(".");
    sb.append(methodReference.getMethodName());
    sb.append("(");
    boolean seenFirstIndex = false;
    for (TypeReference formalType : methodReference.getFormalTypes()) {
      if (seenFirstIndex) {
        sb.append(",");
      }
      seenFirstIndex = true;
      sb.append(formalType.getTypeName());
    }
    sb.append(")");
    return sb.toString();
  }

  public static boolean hasPredictableSourceFileName(String originalClassName, String sourceFile) {
    String synthesizedSourceFileName = getClassSimpleName(originalClassName) + ".java";
    return synthesizedSourceFileName.equals(sourceFile);
  }

  private static String getClassSimpleName(String clazz) {
    int lastIndexOfPeriod = clazz.lastIndexOf('.');
    // Check if we can find a subclass separator.
    int endIndex = clazz.lastIndexOf('$');
    if (lastIndexOfPeriod > endIndex || endIndex < 0) {
      endIndex = clazz.length();
    }
    return clazz.substring(lastIndexOfPeriod + 1, endIndex);
  }

  static RetraceSourceFileResult getSourceFile(
      RetraceClassResult.Element classElement, ClassReference context, String sourceFile) {
    // For inline frames we do not have the class element associated with it.
    if (context == null) {
      return classElement.retraceSourceFile(sourceFile);
    }
    if (context.equals(classElement.getClassReference())) {
      return classElement.retraceSourceFile(sourceFile);
    } else {
      return new RetraceSourceFileResult(
          synthesizeFileName(
              context.getTypeName(),
              classElement.getClassReference().getTypeName(),
              sourceFile,
              true),
          true);
    }
  }

  public static String synthesizeFileName(
      String retracedClassName,
      String minifiedClassName,
      String sourceFile,
      boolean hasRetraceResult) {
    boolean fileNameProbablyChanged =
        hasRetraceResult && !retracedClassName.startsWith(minifiedClassName);
    if (!UNKNOWN_SOURCEFILE_NAMES.contains(sourceFile) && !fileNameProbablyChanged) {
      // We have no new information, only rewrite filename if it is unknown.
      // PG-retrace will always rewrite the filename, but that seems a bit to harsh to do.
      return sourceFile;
    }
    String extension = Files.getFileExtension(sourceFile);
    if (extension.isEmpty()) {
      extension = "java";
    }
    if (!hasRetraceResult) {
      // We have no mapping but but file name is unknown, so the best we can do is take the
      // name of the obfuscated clazz.
      assert minifiedClassName.equals(retracedClassName);
      return getClassSimpleName(minifiedClassName) + "." + extension;
    }
    String newFileName = getClassSimpleName(retracedClassName);
    return newFileName + "." + extension;
  }
}
