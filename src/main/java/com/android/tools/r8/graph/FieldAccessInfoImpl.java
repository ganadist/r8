// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.google.common.collect.Sets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Holds whole program information about the usage of a given field.
 *
 * <p>The information is generated by the {@link com.android.tools.r8.shaking.Enqueuer}.
 */
public class FieldAccessInfoImpl implements FieldAccessInfo {

  public static final FieldAccessInfoImpl MISSING_FIELD_ACCESS_INFO = new FieldAccessInfoImpl(null);

  public static int FLAG_IS_READ_FROM_ANNOTATION = 1 << 0;
  public static int FLAG_IS_READ_FROM_METHOD_HANDLE = 1 << 1;
  public static int FLAG_IS_WRITTEN_FROM_METHOD_HANDLE = 1 << 2;
  public static int FLAG_HAS_REFLECTIVE_ACCESS = 1 << 3;

  // A direct reference to the definition of the field.
  private DexField field;

  // If this field is accessed from a method handle or has a reflective access.
  private int flags;

  // Maps every direct and indirect reference in a read-context to the set of methods in which that
  // reference appears.
  private Map<DexField, ProgramMethodSet> readsWithContexts;

  // Maps every direct and indirect reference in a write-context to the set of methods in which that
  // reference appears.
  private Map<DexField, ProgramMethodSet> writesWithContexts;

  public FieldAccessInfoImpl(DexField field) {
    this.field = field;
  }

  void flattenAccessContexts() {
    flattenAccessContexts(readsWithContexts);
    flattenAccessContexts(writesWithContexts);
  }

  private void flattenAccessContexts(Map<DexField, ProgramMethodSet> accessesWithContexts) {
    if (accessesWithContexts != null) {
      ProgramMethodSet flattenedAccessContexts =
          accessesWithContexts.computeIfAbsent(field, ignore -> ProgramMethodSet.create());
      accessesWithContexts.forEach(
          (access, contexts) -> {
            if (access != field) {
              flattenedAccessContexts.addAll(contexts);
            }
          });
      accessesWithContexts.clear();
      if (!flattenedAccessContexts.isEmpty()) {
        accessesWithContexts.put(field, flattenedAccessContexts);
      }
      assert accessesWithContexts.size() <= 1;
    }
  }

  @Override
  public FieldAccessInfoImpl asMutable() {
    return this;
  }

  @Override
  public DexField getField() {
    return field;
  }

  @Override
  public int getNumberOfReadContexts() {
    return getNumberOfAccessContexts(readsWithContexts);
  }

  @Override
  public int getNumberOfWriteContexts() {
    return getNumberOfAccessContexts(writesWithContexts);
  }

  private int getNumberOfAccessContexts(Map<DexField, ProgramMethodSet> accessesWithContexts) {
    if (accessesWithContexts == null) {
      return 0;
    }
    if (accessesWithContexts.size() == 1) {
      return accessesWithContexts.values().iterator().next().size();
    }
    throw new Unreachable("Should only be querying the number of access contexts after flattening");
  }

  @Override
  public ProgramMethod getUniqueReadContext() {
    if (readsWithContexts != null && readsWithContexts.size() == 1) {
      ProgramMethodSet contexts = readsWithContexts.values().iterator().next();
      if (contexts.size() == 1) {
        return contexts.iterator().next();
      }
    }
    return null;
  }

  @Override
  public void forEachIndirectAccess(Consumer<DexField> consumer) {
    // There can be indirect reads and writes of the same field reference, so we need to keep track
    // of the previously-seen indirect accesses to avoid reporting duplicates.
    Set<DexField> visited = Sets.newIdentityHashSet();
    forEachAccessInMap(
        readsWithContexts, access -> access != field && visited.add(access), consumer);
    forEachAccessInMap(
        writesWithContexts, access -> access != field && visited.add(access), consumer);
  }

  private static void forEachAccessInMap(
      Map<DexField, ProgramMethodSet> accessesWithContexts,
      Predicate<DexField> predicate,
      Consumer<DexField> consumer) {
    if (accessesWithContexts != null) {
      accessesWithContexts.forEach(
          (access, contexts) -> {
            if (predicate.test(access)) {
              consumer.accept(access);
            }
          });
    }
  }

  @Override
  public void forEachIndirectAccessWithContexts(BiConsumer<DexField, ProgramMethodSet> consumer) {
    Map<DexField, ProgramMethodSet> indirectAccessesWithContexts = new IdentityHashMap<>();
    extendAccessesWithContexts(
        indirectAccessesWithContexts, access -> access != field, readsWithContexts);
    extendAccessesWithContexts(
        indirectAccessesWithContexts, access -> access != field, writesWithContexts);
    indirectAccessesWithContexts.forEach(consumer);
  }

  private void extendAccessesWithContexts(
      Map<DexField, ProgramMethodSet> accessesWithContexts,
      Predicate<DexField> predicate,
      Map<DexField, ProgramMethodSet> extension) {
    if (extension != null) {
      extension.forEach(
          (access, contexts) -> {
            if (predicate.test(access)) {
              accessesWithContexts
                  .computeIfAbsent(access, ignore -> ProgramMethodSet.create())
                  .addAll(contexts);
            }
          });
    }
  }

  @Override
  public void forEachReadContext(Consumer<ProgramMethod> consumer) {
    forEachAccessContext(readsWithContexts, consumer);
  }

  @Override
  public void forEachWriteContext(Consumer<ProgramMethod> consumer) {
    forEachAccessContext(writesWithContexts, consumer);
  }

  private void forEachAccessContext(
      Map<DexField, ProgramMethodSet> accessesWithContexts, Consumer<ProgramMethod> consumer) {
    // There can be indirect reads and writes of the same field reference, so we need to keep track
    // of the previously-seen indirect accesses to avoid reporting duplicates.
    ProgramMethodSet visited = ProgramMethodSet.create();
    if (accessesWithContexts != null) {
      for (ProgramMethodSet encodedAccessContexts : accessesWithContexts.values()) {
        for (ProgramMethod encodedAccessContext : encodedAccessContexts) {
          if (visited.add(encodedAccessContext)) {
            consumer.accept(encodedAccessContext);
          }
        }
      }
    }
  }

  @Override
  public boolean hasReflectiveAccess() {
    return (flags & FLAG_HAS_REFLECTIVE_ACCESS) != 0;
  }

  public void setHasReflectiveAccess() {
    flags |= FLAG_HAS_REFLECTIVE_ACCESS;
  }

  /** Returns true if this field is read by the program. */
  @Override
  public boolean isRead() {
    return (readsWithContexts != null && !readsWithContexts.isEmpty()) || isReadFromAnnotation();
  }

  @Override
  public boolean isReadFromAnnotation() {
    return (flags & FLAG_IS_READ_FROM_ANNOTATION) != 0;
  }

  public void setReadFromAnnotation() {
    flags |= FLAG_IS_READ_FROM_ANNOTATION;
  }

  @Override
  public boolean isReadFromMethodHandle() {
    return (flags & FLAG_IS_READ_FROM_METHOD_HANDLE) != 0;
  }

  public void setReadFromMethodHandle() {
    flags |= FLAG_IS_READ_FROM_METHOD_HANDLE;
  }

  /** Returns true if this field is written by the program. */
  @Override
  public boolean isWritten() {
    return writesWithContexts != null && !writesWithContexts.isEmpty();
  }

  @Override
  public boolean isWrittenFromMethodHandle() {
    return (flags & FLAG_IS_WRITTEN_FROM_METHOD_HANDLE) != 0;
  }

  public void setWrittenFromMethodHandle() {
    flags |= FLAG_IS_WRITTEN_FROM_METHOD_HANDLE;
  }

  /**
   * Returns true if this field is written by a method for which {@param predicate} returns true.
   */
  @Override
  public boolean isWrittenInMethodSatisfying(Predicate<ProgramMethod> predicate) {
    if (writesWithContexts != null) {
      for (ProgramMethodSet encodedWriteContexts : writesWithContexts.values()) {
        for (ProgramMethod encodedWriteContext : encodedWriteContexts) {
          if (predicate.test(encodedWriteContext)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns true if this field is only written by methods for which {@param predicate} returns
   * true.
   */
  @Override
  public boolean isWrittenOnlyInMethodSatisfying(Predicate<ProgramMethod> predicate) {
    if (writesWithContexts != null) {
      for (ProgramMethodSet encodedWriteContexts : writesWithContexts.values()) {
        for (ProgramMethod encodedWriteContext : encodedWriteContexts) {
          if (!predicate.test(encodedWriteContext)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  /**
   * Returns true if this field is written by a method in the program other than {@param method}.
   */
  @Override
  public boolean isWrittenOutside(DexEncodedMethod method) {
    if (writesWithContexts != null) {
      for (ProgramMethodSet encodedWriteContexts : writesWithContexts.values()) {
        for (ProgramMethod encodedWriteContext : encodedWriteContexts) {
          if (encodedWriteContext.getDefinition() != method) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public boolean recordRead(DexField access, ProgramMethod context) {
    if (readsWithContexts == null) {
      readsWithContexts = new IdentityHashMap<>();
    }
    return readsWithContexts
        .computeIfAbsent(access, ignore -> ProgramMethodSet.create())
        .add(context);
  }

  public boolean recordWrite(DexField access, ProgramMethod context) {
    if (writesWithContexts == null) {
      writesWithContexts = new IdentityHashMap<>();
    }
    return writesWithContexts
        .computeIfAbsent(access, ignore -> ProgramMethodSet.create())
        .add(context);
  }

  public void clearReads() {
    readsWithContexts = null;
  }

  public void clearWrites() {
    writesWithContexts = null;
  }

  public FieldAccessInfoImpl rewrittenWithLens(DexDefinitionSupplier definitions, GraphLens lens) {
    FieldAccessInfoImpl rewritten = new FieldAccessInfoImpl(lens.lookupField(field));
    rewritten.flags = flags;
    if (readsWithContexts != null) {
      rewritten.readsWithContexts = new IdentityHashMap<>();
      readsWithContexts.forEach(
          (access, contexts) -> {
            ProgramMethodSet newContexts =
                rewritten.readsWithContexts.computeIfAbsent(
                    lens.lookupField(access), ignore -> ProgramMethodSet.create());
            for (ProgramMethod context : contexts) {
              newContexts.add(lens.mapProgramMethod(context, definitions));
            }
          });
    }
    if (writesWithContexts != null) {
      rewritten.writesWithContexts = new IdentityHashMap<>();
      writesWithContexts.forEach(
          (access, contexts) -> {
            ProgramMethodSet newContexts =
                rewritten.writesWithContexts.computeIfAbsent(
                    lens.lookupField(access), ignore -> ProgramMethodSet.create());
            for (ProgramMethod context : contexts) {
              newContexts.add(lens.mapProgramMethod(context, definitions));
            }
          });
    }
    return rewritten;
  }
}
