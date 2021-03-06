// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace;

import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.retrace.RetraceClassResult.Element;
import com.android.tools.r8.retrace.RetraceRegularExpression.RetraceString.RetraceStringBuilder;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RetraceRegularExpression {

  private final RetraceApi retracer;
  private final List<String> stackTrace;
  private final DiagnosticsHandler diagnosticsHandler;
  private final String regularExpression;

  private static final int NO_MATCH = -1;

  private final RegularExpressionGroup[] syntheticGroups =
      new RegularExpressionGroup[] {new SourceFileLineNumberGroup()};

  private final RegularExpressionGroup[] groups =
      new RegularExpressionGroup[] {
        new TypeNameGroup(),
        new BinaryNameGroup(),
        new MethodNameGroup(),
        new FieldNameGroup(),
        new SourceFileGroup(),
        new LineNumberGroup(),
        new FieldOrReturnTypeGroup(),
        new MethodArgumentsGroup()
      };

  private static final String CAPTURE_GROUP_PREFIX = "captureGroup";

  RetraceRegularExpression(
      RetraceApi retracer,
      List<String> stackTrace,
      DiagnosticsHandler diagnosticsHandler,
      String regularExpression) {
    this.retracer = retracer;
    this.stackTrace = stackTrace;
    this.diagnosticsHandler = diagnosticsHandler;
    this.regularExpression = regularExpression;
  }

  public RetraceCommandLineResult retrace() {
    List<RegularExpressionGroupHandler> handlers = new ArrayList<>();
    String regularExpression = registerGroups(this.regularExpression, handlers);
    Pattern compiledPattern = Pattern.compile(regularExpression);
    List<String> result = new ArrayList<>();
    for (String string : stackTrace) {
      Matcher matcher = compiledPattern.matcher(string);
      List<RetraceString> retracedStrings =
          Lists.newArrayList(RetraceStringBuilder.create(string).build());
      if (matcher.matches()) {
        for (RegularExpressionGroupHandler handler : handlers) {
          retracedStrings = handler.handleMatch(retracedStrings, matcher, retracer);
        }
      }
      if (retracedStrings.isEmpty()) {
        // We could not find a match. Output the identity.
        result.add(string);
      } else {
        boolean isAmbiguous = retracedStrings.size() > 1 && retracedStrings.get(0).isAmbiguous;
        if (isAmbiguous) {
          retracedStrings.sort(new RetraceLineComparator());
        }
        ClassReference previousContext = null;
        for (RetraceString retracedString : retracedStrings) {
          String finalString = retracedString.getRetracedString();
          if (!isAmbiguous) {
            result.add(finalString);
            continue;
          }
          assert retracedString.getClassContext() != null;
          ClassReference currentContext = retracedString.getClassContext().getClassReference();
          if (currentContext.equals(previousContext)) {
            int firstNonWhitespaceCharacter = StringUtils.firstNonWhitespaceCharacter(finalString);
            finalString =
                finalString.substring(0, firstNonWhitespaceCharacter)
                    + "<OR> "
                    + finalString.substring(firstNonWhitespaceCharacter);
          }
          previousContext = currentContext;
          result.add(finalString);
        }
      }
    }
    return new RetraceCommandLineResult(result);
  }

  static class RetraceLineComparator extends AmbiguousComparator<RetraceString> {

    RetraceLineComparator() {
      super(
          (line, t) -> {
            switch (t) {
              case CLASS:
                return line.getClassContext().getClassReference().getTypeName();
              case METHOD:
                return line.getMethodContext().getMethodReference().getMethodName();
              case SOURCE:
                return line.getSource();
              case LINE:
                return line.getLineNumber() + "";
              default:
                assert false;
            }
            throw new RuntimeException("Comparator key is unknown");
          });
    }
  }

  private String registerGroups(
      String regularExpression, List<RegularExpressionGroupHandler> handlers) {
    int currentIndex = 0;
    int captureGroupIndex = 0;
    regularExpression = registerSyntheticGroups(regularExpression);
    while (currentIndex < regularExpression.length()) {
      RegularExpressionGroup firstGroup = null;
      int firstIndexFromCurrent = regularExpression.length();
      for (RegularExpressionGroup group : groups) {
        int firstIndex =
            firstIndexOfGroup(
                currentIndex, firstIndexFromCurrent, regularExpression, group.shortName());
        if (firstIndex > NO_MATCH) {
          firstGroup = group;
          firstIndexFromCurrent = firstIndex;
        }
      }
      if (firstGroup != null) {
        String captureGroupName = CAPTURE_GROUP_PREFIX + (captureGroupIndex++);
        String patternToInsert = "(?<" + captureGroupName + ">" + firstGroup.subExpression() + ")";
        regularExpression =
            regularExpression.substring(0, firstIndexFromCurrent)
                + patternToInsert
                + regularExpression.substring(
                    firstIndexFromCurrent + firstGroup.shortName().length());
        handlers.add(firstGroup.createHandler(captureGroupName));
        firstIndexFromCurrent += patternToInsert.length();
      }
      currentIndex = firstIndexFromCurrent;
    }
    return regularExpression;
  }

  private int firstIndexOfGroup(int startIndex, int endIndex, String expression, String shortName) {
    int nextIndexOf = startIndex;
    while (nextIndexOf != NO_MATCH) {
      nextIndexOf = expression.indexOf(shortName, nextIndexOf);
      if (nextIndexOf > NO_MATCH) {
        if (nextIndexOf < endIndex && !isEscaped(expression, nextIndexOf)) {
          return nextIndexOf;
        }
        nextIndexOf++;
      }
    }
    return NO_MATCH;
  }

  private boolean isEscaped(String expression, int index) {
    boolean escaped = false;
    while (index > 0 && expression.charAt(--index) == '\\') {
      escaped = !escaped;
    }
    return escaped;
  }

  private String registerSyntheticGroups(String regularExpression) {
    boolean modifiedExpression;
    do {
      modifiedExpression = false;
      for (RegularExpressionGroup syntheticGroup : syntheticGroups) {
        int firstIndex =
            firstIndexOfGroup(
                0, regularExpression.length(), regularExpression, syntheticGroup.shortName());
        if (firstIndex > NO_MATCH) {
          regularExpression =
              regularExpression.substring(0, firstIndex)
                  + syntheticGroup.subExpression()
                  + regularExpression.substring(firstIndex + syntheticGroup.shortName().length());
          // Loop as long as we can replace.
          modifiedExpression = true;
        }
      }
    } while (modifiedExpression);
    return regularExpression;
  }

  static class RetraceString {

    private final Element classContext;
    private final ClassNameGroup classNameGroup;
    private final ClassReference qualifiedContext;
    private final RetraceMethodResult.Element methodContext;
    private final TypeReference typeOrReturnTypeContext;
    private final boolean hasTypeOrReturnTypeContext;
    private final String retracedString;
    private final int adjustedIndex;
    private final boolean isAmbiguous;
    private final int lineNumber;
    private final String source;

    private RetraceString(
        Element classContext,
        ClassNameGroup classNameGroup,
        ClassReference qualifiedContext,
        RetraceMethodResult.Element methodContext,
        TypeReference typeOrReturnTypeContext,
        boolean hasTypeOrReturnTypeContext,
        String retracedString,
        int adjustedIndex,
        boolean isAmbiguous,
        int lineNumber,
        String source) {
      this.classContext = classContext;
      this.classNameGroup = classNameGroup;
      this.qualifiedContext = qualifiedContext;
      this.methodContext = methodContext;
      this.typeOrReturnTypeContext = typeOrReturnTypeContext;
      this.hasTypeOrReturnTypeContext = hasTypeOrReturnTypeContext;
      this.retracedString = retracedString;
      this.adjustedIndex = adjustedIndex;
      this.isAmbiguous = isAmbiguous;
      this.lineNumber = lineNumber;
      this.source = source;
    }

    String getRetracedString() {
      return retracedString;
    }

    boolean hasTypeOrReturnTypeContext() {
      return hasTypeOrReturnTypeContext;
    }

    Element getClassContext() {
      return classContext;
    }

    RetraceMethodResult.Element getMethodContext() {
      return methodContext;
    }

    TypeReference getTypeOrReturnTypeContext() {
      return typeOrReturnTypeContext;
    }

    public ClassReference getQualifiedContext() {
      return qualifiedContext;
    }

    RetraceStringBuilder transform() {
      return RetraceStringBuilder.create(this);
    }

    public int getLineNumber() {
      return lineNumber;
    }

    public String getSource() {
      return source;
    }

    static class RetraceStringBuilder {

      private Element classContext;
      private ClassNameGroup classNameGroup;
      private ClassReference qualifiedContext;
      private RetraceMethodResult.Element methodContext;
      private TypeReference typeOrReturnTypeContext;
      private boolean hasTypeOrReturnTypeContext;
      private String retracedString;
      private int adjustedIndex;
      private boolean isAmbiguous;
      private int lineNumber;
      private String source;

      private int maxReplaceStringIndex = NO_MATCH;

      private RetraceStringBuilder(
          Element classContext,
          ClassNameGroup classNameGroup,
          ClassReference qualifiedContext,
          RetraceMethodResult.Element methodContext,
          TypeReference typeOrReturnTypeContext,
          boolean hasTypeOrReturnTypeContext,
          String retracedString,
          int adjustedIndex,
          boolean isAmbiguous,
          int lineNumber,
          String source) {
        this.classContext = classContext;
        this.classNameGroup = classNameGroup;
        this.qualifiedContext = qualifiedContext;
        this.methodContext = methodContext;
        this.typeOrReturnTypeContext = typeOrReturnTypeContext;
        this.hasTypeOrReturnTypeContext = hasTypeOrReturnTypeContext;
        this.retracedString = retracedString;
        this.adjustedIndex = adjustedIndex;
        this.isAmbiguous = isAmbiguous;
        this.lineNumber = lineNumber;
        this.source = source;
      }

      static RetraceStringBuilder create(String string) {
        return new RetraceStringBuilder(
            null, null, null, null, null, false, string, 0, false, 0, "");
      }

      static RetraceStringBuilder create(RetraceString string) {
        return new RetraceStringBuilder(
            string.classContext,
            string.classNameGroup,
            string.qualifiedContext,
            string.methodContext,
            string.typeOrReturnTypeContext,
            string.hasTypeOrReturnTypeContext,
            string.retracedString,
            string.adjustedIndex,
            string.isAmbiguous,
            string.lineNumber,
            string.source);
      }

      RetraceStringBuilder setClassContext(Element classContext, ClassNameGroup classNameGroup) {
        this.classContext = classContext;
        this.classNameGroup = classNameGroup;
        return this;
      }

      RetraceStringBuilder setMethodContext(RetraceMethodResult.Element methodContext) {
        this.methodContext = methodContext;
        return this;
      }

      RetraceStringBuilder setTypeOrReturnTypeContext(TypeReference typeOrReturnTypeContext) {
        hasTypeOrReturnTypeContext = true;
        this.typeOrReturnTypeContext = typeOrReturnTypeContext;
        return this;
      }

      RetraceStringBuilder setQualifiedContext(ClassReference qualifiedContext) {
        this.qualifiedContext = qualifiedContext;
        return this;
      }

      RetraceStringBuilder setAmbiguous(boolean isAmbiguous) {
        this.isAmbiguous = isAmbiguous;
        return this;
      }

      RetraceStringBuilder setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
        return this;
      }

      RetraceStringBuilder setSource(String source) {
        this.source = source;
        return this;
      }

      RetraceStringBuilder replaceInString(String oldString, String newString) {
        int oldStringStartIndex = retracedString.indexOf(oldString);
        assert oldStringStartIndex > NO_MATCH;
        int oldStringEndIndex = oldStringStartIndex + oldString.length();
        return replaceInStringRaw(newString, oldStringStartIndex, oldStringEndIndex);
      }

      RetraceStringBuilder replaceInString(String newString, int originalFrom, int originalTo) {
        return replaceInStringRaw(
            newString, originalFrom + adjustedIndex, originalTo + adjustedIndex);
      }

      RetraceStringBuilder replaceInStringRaw(String newString, int from, int to) {
        assert from <= to;
        assert from > maxReplaceStringIndex;
        String prefix = retracedString.substring(0, from);
        String postFix = retracedString.substring(to);
        this.retracedString = prefix + newString + postFix;
        this.adjustedIndex = adjustedIndex + newString.length() - (to - from);
        maxReplaceStringIndex = prefix.length() + newString.length();
        return this;
      }

      RetraceString build() {
        return new RetraceString(
            classContext,
            classNameGroup,
            qualifiedContext,
            methodContext,
            typeOrReturnTypeContext,
            hasTypeOrReturnTypeContext,
            retracedString,
            adjustedIndex,
            isAmbiguous,
            lineNumber,
            source);
      }
    }
  }

  private interface RegularExpressionGroupHandler {

    List<RetraceString> handleMatch(
        List<RetraceString> strings, Matcher matcher, RetraceApi retracer);
  }

  private abstract static class RegularExpressionGroup {

    abstract String shortName();

    abstract String subExpression();

    abstract RegularExpressionGroupHandler createHandler(String captureGroup);

    boolean isSynthetic() {
      return false;
    }
  }

  // TODO(b/145731185): Extend support for identifiers with strings inside back ticks.
  private static final String javaIdentifierSegment =
      "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";

  private abstract static class ClassNameGroup extends RegularExpressionGroup {

    abstract String getClassName(ClassReference classReference);

    abstract ClassReference classFromMatch(String match);

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (strings, matcher, retracer) -> {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return strings;
        }
        String typeName = matcher.group(captureGroup);
        RetraceClassResult retraceResult = retracer.retrace(classFromMatch(typeName));
        List<RetraceString> retracedStrings = new ArrayList<>();
        for (RetraceString retraceString : strings) {
          retraceResult.forEach(
              element -> {
                retracedStrings.add(
                    retraceString
                        .transform()
                        .setClassContext(element, this)
                        .setMethodContext(null)
                        .replaceInString(
                            getClassName(element.getClassReference()),
                            matcher.start(captureGroup),
                            matcher.end(captureGroup))
                        .build());
              });
        }
        return retracedStrings;
      };
    }
  }

  private static class TypeNameGroup extends ClassNameGroup {

    @Override
    String shortName() {
      return "%c";
    }

    @Override
    String subExpression() {
      return "(" + javaIdentifierSegment + "\\.)*" + javaIdentifierSegment;
    }

    @Override
    String getClassName(ClassReference classReference) {
      return classReference.getTypeName();
    }

    @Override
    ClassReference classFromMatch(String match) {
      return Reference.classFromTypeName(match);
    }
  }

  private static class BinaryNameGroup extends ClassNameGroup {

    @Override
    String shortName() {
      return "%C";
    }

    @Override
    String subExpression() {
      return "(?:" + javaIdentifierSegment + "\\/)*" + javaIdentifierSegment;
    }

    @Override
    String getClassName(ClassReference classReference) {
      return classReference.getBinaryName();
    }

    @Override
    ClassReference classFromMatch(String match) {
      return Reference.classFromBinaryName(match);
    }
  }

  private static class MethodNameGroup extends RegularExpressionGroup {

    @Override
    String shortName() {
      return "%m";
    }

    @Override
    String subExpression() {
      return "(?:(" + javaIdentifierSegment + "|\\<init\\>|\\<clinit\\>))";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (strings, matcher, retracer) -> {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return strings;
        }
        String methodName = matcher.group(captureGroup);
        List<RetraceString> retracedStrings = new ArrayList<>();
        for (RetraceString retraceString : strings) {
          if (retraceString.classContext == null) {
            retracedStrings.add(retraceString);
            continue;
          }
          retraceString
              .getClassContext()
              .lookupMethod(methodName)
              .forEach(
                  element -> {
                    MethodReference methodReference = element.getMethodReference();
                    if (retraceString.hasTypeOrReturnTypeContext()) {
                      if (methodReference.getReturnType() == null
                          && retraceString.getTypeOrReturnTypeContext() != null) {
                        return;
                      } else if (methodReference.getReturnType() != null
                          && !methodReference
                              .getReturnType()
                              .equals(retraceString.getTypeOrReturnTypeContext())) {
                        return;
                      }
                    }
                    RetraceStringBuilder newRetraceString = retraceString.transform();
                    ClassReference existingClass =
                        retraceString.getClassContext().getClassReference();
                    ClassReference holder = methodReference.getHolderClass();
                    if (holder != existingClass) {
                      // The element is defined on another holder.
                      newRetraceString
                          .replaceInString(
                              newRetraceString.classNameGroup.getClassName(existingClass),
                              newRetraceString.classNameGroup.getClassName(holder))
                          .setQualifiedContext(holder);
                    }
                    newRetraceString
                        .setMethodContext(element)
                        .setAmbiguous(element.getRetraceMethodResult().isAmbiguous())
                        .replaceInString(
                            methodReference.getMethodName(),
                            matcher.start(captureGroup),
                            matcher.end(captureGroup));
                    retracedStrings.add(newRetraceString.build());
                  });
        }
        return retracedStrings;
      };
    }
  }

  private static class FieldNameGroup extends RegularExpressionGroup {

    @Override
    String shortName() {
      return "%f";
    }

    @Override
    String subExpression() {
      return javaIdentifierSegment;
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (strings, matcher, retracer) -> {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return strings;
        }
        String methodName = matcher.group(captureGroup);
        List<RetraceString> retracedStrings = new ArrayList<>();
        for (RetraceString retraceString : strings) {
          if (retraceString.getClassContext() == null) {
            retracedStrings.add(retraceString);
            continue;
          }
          retraceString
              .getClassContext()
              .lookupField(methodName)
              .forEach(
                  element -> {
                    RetraceStringBuilder newRetraceString = retraceString.transform();
                    ClassReference existingClass =
                        retraceString.getClassContext().getClassReference();
                    ClassReference holder = element.getFieldReference().getHolderClass();
                    if (holder != existingClass) {
                      // The element is defined on another holder.
                      newRetraceString
                          .replaceInString(
                              newRetraceString.classNameGroup.getClassName(existingClass),
                              newRetraceString.classNameGroup.getClassName(holder))
                          .setQualifiedContext(holder);
                    }
                    newRetraceString.replaceInString(
                        element.getFieldReference().getFieldName(),
                        matcher.start(captureGroup),
                        matcher.end(captureGroup));
                    retracedStrings.add(newRetraceString.build());
                  });
        }
        return retracedStrings;
      };
    }
  }

  private static class SourceFileGroup extends RegularExpressionGroup {

    @Override
    String shortName() {
      return "%s";
    }

    @Override
    String subExpression() {
      return "(?:(\\w*[\\. ])?(\\w*)?)";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (strings, matcher, retracer) -> {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return strings;
        }
        String fileName = matcher.group(captureGroup);
        List<RetraceString> retracedStrings = new ArrayList<>();
        for (RetraceString retraceString : strings) {
          if (retraceString.classContext == null) {
            retracedStrings.add(retraceString);
            continue;
          }
          RetraceSourceFileResult sourceFileResult =
              retraceString.getMethodContext() != null
                  ? retraceString.getMethodContext().retraceSourceFile(fileName)
                  : RetraceUtils.getSourceFile(
                      retraceString.getClassContext(),
                      retraceString.getQualifiedContext(),
                      fileName);
          retracedStrings.add(
              retraceString
                  .transform()
                  .setSource(fileName)
                  .replaceInString(
                      sourceFileResult.getFilename(),
                      matcher.start(captureGroup),
                      matcher.end(captureGroup))
                  .build());
        }
        return retracedStrings;
      };
    }
  }

  private class LineNumberGroup extends RegularExpressionGroup {

    @Override
    String shortName() {
      return "%l";
    }

    @Override
    String subExpression() {
      return "\\d*";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (strings, matcher, retracer) -> {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return strings;
        }
        String lineNumberAsString = matcher.group(captureGroup);
        int lineNumber =
            lineNumberAsString.isEmpty() ? NO_MATCH : Integer.parseInt(lineNumberAsString);
        List<RetraceString> retracedStrings = new ArrayList<>();
        boolean seenRange = false;
        for (RetraceString retraceString : strings) {
          RetraceMethodResult.Element methodContext = retraceString.methodContext;
          if (methodContext == null || methodContext.getMethodReference().isUnknown()) {
            retracedStrings.add(retraceString);
            continue;
          }
          if (methodContext.hasNoLineNumberRange()) {
            continue;
          }
          seenRange = true;
          Set<MethodReference> narrowedSet =
              methodContext.getRetraceMethodResult().narrowByLine(lineNumber).stream()
                  .map(RetraceMethodResult.Element::getMethodReference)
                  .collect(Collectors.toSet());
          if (!narrowedSet.contains(methodContext.getMethodReference())) {
            // Prune the retraceString since we now have line number information and this is not
            // a part of the result.
            diagnosticsHandler.info(
                new StringDiagnostic(
                    "Pruning "
                        + retraceString.getRetracedString()
                        + " from result because method is not defined on line number "
                        + lineNumber));
            continue;
          }
          // The same method can be represented multiple times if it has multiple mappings.
          if (!methodContext.containsMinifiedLineNumber(lineNumber)) {
            diagnosticsHandler.info(
                new StringDiagnostic(
                    "Pruning "
                        + retraceString.getRetracedString()
                        + " from result because method is not in range on line number "
                        + lineNumber));
            continue;
          }
          int originalLineNumber = methodContext.getOriginalLineNumber(lineNumber);
          retracedStrings.add(
              retraceString
                  .transform()
                  .setAmbiguous(false)
                  .setLineNumber(originalLineNumber)
                  .replaceInString(
                      originalLineNumber + "",
                      matcher.start(captureGroup),
                      matcher.end(captureGroup))
                  .build());
        }
        return seenRange ? retracedStrings : strings;
      };
    }
  }

  private static class SourceFileLineNumberGroup extends RegularExpressionGroup {

    @Override
    String shortName() {
      return "%S";
    }

    @Override
    String subExpression() {
      return "%s(?::%l)?";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      throw new Unreachable("Should never be called");
    }

    @Override
    boolean isSynthetic() {
      return true;
    }
  }

  private static final String JAVA_TYPE_REGULAR_EXPRESSION =
      "(" + javaIdentifierSegment + "\\.)*" + javaIdentifierSegment + "[\\[\\]]*";

  private static class FieldOrReturnTypeGroup extends RegularExpressionGroup {

    @Override
    String shortName() {
      return "%t";
    }

    @Override
    String subExpression() {
      return JAVA_TYPE_REGULAR_EXPRESSION;
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (strings, matcher, retracer) -> {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return strings;
        }
        String typeName = matcher.group(captureGroup);
        String descriptor = DescriptorUtils.javaTypeToDescriptor(typeName);
        if (!DescriptorUtils.isDescriptor(descriptor) && !"V".equals(descriptor)) {
          return strings;
        }
        TypeReference typeReference = Reference.returnTypeFromDescriptor(descriptor);
        List<RetraceString> retracedStrings = new ArrayList<>();
        RetraceTypeResult retracedType = retracer.retrace(typeReference);
        for (RetraceString retraceString : strings) {
          retracedType.forEach(
              element -> {
                TypeReference retracedReference = element.getTypeReference();
                retracedStrings.add(
                    retraceString
                        .transform()
                        .setTypeOrReturnTypeContext(retracedReference)
                        .replaceInString(
                            retracedReference == null ? "void" : retracedReference.getTypeName(),
                            matcher.start(captureGroup),
                            matcher.end(captureGroup))
                        .build());
              });
        }
        return retracedStrings;
      };
    }
  }

  private class MethodArgumentsGroup extends RegularExpressionGroup {

    @Override
    String shortName() {
      return "%a";
    }

    @Override
    String subExpression() {
      return "((" + JAVA_TYPE_REGULAR_EXPRESSION + "\\,)*" + JAVA_TYPE_REGULAR_EXPRESSION + ")?";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (strings, matcher, retracer) -> {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return strings;
        }
        Set<List<TypeReference>> initialValue = new LinkedHashSet<>();
        initialValue.add(new ArrayList<>());
        Set<List<TypeReference>> allRetracedReferences =
            Arrays.stream(matcher.group(captureGroup).split(","))
                .map(String::trim)
                .reduce(
                    initialValue,
                    (acc, typeName) -> {
                      String descriptor = DescriptorUtils.javaTypeToDescriptor(typeName);
                      if (!DescriptorUtils.isDescriptor(descriptor) && !"V".equals(descriptor)) {
                        return acc;
                      }
                      TypeReference typeReference = Reference.returnTypeFromDescriptor(descriptor);
                      Set<List<TypeReference>> retracedTypes = new LinkedHashSet<>();
                      retracer
                          .retrace(typeReference)
                          .forEach(
                              element -> {
                                for (List<TypeReference> currentReferences : acc) {
                                  ArrayList<TypeReference> newList =
                                      new ArrayList<>(currentReferences);
                                  newList.add(element.getTypeReference());
                                  retracedTypes.add(newList);
                                }
                              });
                      return retracedTypes;
                    },
                    (l1, l2) -> {
                      l1.addAll(l2);
                      return l1;
                    });
        List<RetraceString> retracedStrings = new ArrayList<>();
        for (RetraceString retraceString : strings) {
          if (retraceString.getMethodContext() != null
              && !allRetracedReferences.contains(
                  retraceString.getMethodContext().getMethodReference().getFormalTypes())) {
            // Prune the string since we now know the formals.
            String formals =
                retraceString.getMethodContext().getMethodReference().getFormalTypes().stream()
                    .map(TypeReference::getTypeName)
                    .collect(Collectors.joining(","));
            diagnosticsHandler.info(
                new StringDiagnostic(
                    "Pruning "
                        + retraceString.getRetracedString()
                        + " from result because formals ("
                        + formals
                        + ") do not match result set."));
            continue;
          }
          for (List<TypeReference> retracedReferences : allRetracedReferences) {
            retracedStrings.add(
                retraceString
                    .transform()
                    .replaceInString(
                        retracedReferences.stream()
                            .map(TypeReference::getTypeName)
                            .collect(Collectors.joining(",")),
                        matcher.start(captureGroup),
                        matcher.end(captureGroup))
                    .build());
          }
        }
        return retracedStrings;
      };
    }
  }
}
