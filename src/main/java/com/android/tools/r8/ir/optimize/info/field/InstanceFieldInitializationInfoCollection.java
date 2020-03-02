// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info.field;

import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexField;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A mapping from instance fields of a class to information about how a particular constructor
 * initializes these instance fields.
 *
 * <p>Returns {@link UnknownInstanceFieldInitializationInfo} if no information is known about the
 * initialization of a given instance field.
 */
public abstract class InstanceFieldInitializationInfoCollection {

  public static Builder builder() {
    return new Builder();
  }

  public abstract void forEach(
      DexDefinitionSupplier definitions,
      BiConsumer<DexEncodedField, InstanceFieldInitializationInfo> consumer);

  public abstract InstanceFieldInitializationInfo get(DexEncodedField field);

  public abstract boolean isEmpty();

  public static class Builder {

    Map<DexField, InstanceFieldInitializationInfo> infos = new IdentityHashMap<>();

    public void recordInitializationInfo(
        DexEncodedField field, InstanceFieldInitializationInfo info) {
      assert !infos.containsKey(field.field);
      infos.put(field.field, info);
    }

    public InstanceFieldInitializationInfoCollection build() {
      if (infos.isEmpty()) {
        return EmptyInstanceFieldInitializationInfoCollection.getInstance();
      }
      return new NonTrivialInstanceFieldInitializationInfoCollection(infos);
    }
  }
}