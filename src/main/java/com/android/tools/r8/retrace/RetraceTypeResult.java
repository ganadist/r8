// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace;

import com.android.tools.r8.references.Reference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.retrace.RetraceTypeResult.Element;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class RetraceTypeResult extends Result<Element, RetraceTypeResult> {

  private final TypeReference obfuscatedType;
  private final RetraceApi retracer;

  RetraceTypeResult(TypeReference obfuscatedType, RetraceApi retracer) {
    this.obfuscatedType = obfuscatedType;
    this.retracer = retracer;
  }

  @Override
  public Stream<Element> stream() {
    // Handle void and primitive types as single element results.
    if (obfuscatedType == null || obfuscatedType.isPrimitive()) {
      return Stream.of(new Element(obfuscatedType));
    }
    if (obfuscatedType.isArray()) {
      int dimensions = obfuscatedType.asArray().getDimensions();
      return retracer.retrace(obfuscatedType.asArray().getBaseType()).stream()
          .map(base -> new Element(Reference.array(base.getTypeReference(), dimensions)));
    }
    return retracer.retrace(obfuscatedType.asClass()).stream()
        .map(clazz -> new Element(clazz.getClassReference()));
  }

  @Override
  public RetraceTypeResult forEach(Consumer<Element> resultConsumer) {
    stream().forEach(resultConsumer);
    return this;
  }

  public static class Element {

    private final TypeReference typeReference;

    public Element(TypeReference typeReference) {
      this.typeReference = typeReference;
    }

    public TypeReference getTypeReference() {
      return typeReference;
    }
  }
}
