// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.utils.InternalResource;
import com.google.common.base.MoreObjects;

public abstract class DexClass extends DexItem implements DexClassPromise {
  public interface Factory {
    DexClass create(DexType type, Origin origin, DexAccessFlags accessFlags, DexType superType,
        DexTypeList interfaces, DexString sourceFile, DexAnnotationSet annotations,
        DexEncodedField[] staticFields, DexEncodedField[] instanceFields,
        DexEncodedMethod[] directMethods, DexEncodedMethod[] virtualMethods);
  }

  public enum Origin {
    Dex, ClassFile, Synthetic
  }

  private static final DexEncodedMethod[] NO_METHODS = {};
  private static final DexEncodedField[] NO_FIELDS = {};

  public final Origin origin;
  public final DexType type;
  public final DexAccessFlags accessFlags;
  public final DexType superType;
  public final DexTypeList interfaces;
  public final DexString sourceFile;
  public DexEncodedField[] staticFields;
  public DexEncodedField[] instanceFields;
  public DexEncodedMethod[] directMethods;
  public DexEncodedMethod[] virtualMethods;
  public DexAnnotationSet annotations;

  public DexClass(
      DexString sourceFile, DexTypeList interfaces, DexAccessFlags accessFlags, DexType superType,
      DexType type, DexEncodedField[] staticFields, DexEncodedField[] instanceFields,
      DexEncodedMethod[] directMethods, DexEncodedMethod[] virtualMethods,
      DexAnnotationSet annotations, Origin origin) {
    this.origin = origin;
    this.sourceFile = sourceFile;
    this.interfaces = interfaces;
    this.accessFlags = accessFlags;
    this.superType = superType;
    this.type = type;
    this.staticFields = staticFields;
    this.instanceFields = instanceFields;
    this.directMethods = directMethods;
    this.virtualMethods = virtualMethods;
    this.annotations = annotations;
    if (type == superType) {
      throw new CompilationError("Class " + type.toString() + " cannot extend itself");
    }
    for (DexType interfaceType : interfaces.values) {
      if (type == interfaceType) {
        throw new CompilationError("Interface " + type.toString() + " cannot implement itself");
      }
    }
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    throw new Unreachable();
  }

  public DexEncodedMethod[] directMethods() {
    return MoreObjects.firstNonNull(directMethods, NO_METHODS);
  }

  public DexEncodedMethod[] virtualMethods() {
    return MoreObjects.firstNonNull(virtualMethods, NO_METHODS);
  }


  public DexEncodedField[] staticFields() {
    return MoreObjects.firstNonNull(staticFields, NO_FIELDS);
  }

  public DexEncodedField[] instanceFields() {
    return MoreObjects.firstNonNull(instanceFields, NO_FIELDS);
  }

  /**
   * Find direct method in this class matching method
   */
  public DexEncodedMethod findDirectTarget(DexMethod method) {
    return findTarget(directMethods(), method);
  }

  /**
   * Find static field in this class matching field
   */
  public DexEncodedField findStaticTarget(DexField field) {
    return findTarget(staticFields(), field);
  }

  /**
   * Find virtual method in this class matching method
   */
  public DexEncodedMethod findVirtualTarget(DexMethod method) {
    return findTarget(virtualMethods(), method);
  }

  /**
   * Find instance field in this class matching field
   */
  public DexEncodedField findInstanceTarget(DexField field) {
    return findTarget(instanceFields(), field);
  }

  private <T extends DexItem, S extends Descriptor<T, S>> T findTarget(T[] items, S descriptor) {
    for (T entry : items) {
      if (descriptor.match(entry)) {
        return entry;
      }
    }
    return null;
  }

  // Tells whether this is an interface.
  public boolean isInterface() {
    return accessFlags.isInterface();
  }

  public abstract void addDependencies(MixedSectionCollection collector);

  @Override
  public boolean isProgramClass() {
    return false;
  }

  public DexProgramClass asProgramClass() {
    return null;
  }

  @Override
  public boolean isClasspathClass() {
    return false;
  }

  public DexClasspathClass asClasspathClass() {
    return null;
  }

  @Override
  public boolean isLibraryClass() {
    return false;
  }

  public DexLibraryClass asLibraryClass() {
    return null;
  }

  public DexEncodedMethod getClassInitializer(DexItemFactory factory) {
    for (DexEncodedMethod method : directMethods()) {
      if (factory.isClassConstructor(method.method)) {
        return method;
      }
    }
    return null;
  }

  @Override
  public Origin getOrigin() {
    return this.origin;
  }

  @Override
  public DexClass get() {
    return this;
  }

  @Override
  public DexType getType() {
    return type;
  }

  /** Get a class factory for a particular resource kind */
  public static Factory factoryForResourceKind(InternalResource.Kind kind) {
    switch (kind) {
      case PROGRAM:
        return DexProgramClass::new;
      case CLASSPATH:
        return DexClasspathClass::new;
      case LIBRARY:
        return DexLibraryClass::new;
    }
    throw new Unreachable();
  }
}
