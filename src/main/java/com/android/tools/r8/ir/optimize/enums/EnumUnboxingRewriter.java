// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.enums;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.ParameterAnnotationsList;
import com.android.tools.r8.ir.analysis.type.DestructivePhiTypeUpdater;
import com.android.tools.r8.ir.analysis.type.PrimitiveTypeLatticeElement;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.code.Phi;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.origin.SynthesizedOrigin;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.AppInfoWithLiveness.EnumValueInfo;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class EnumUnboxingRewriter {

  public static final String ENUM_UNBOXING_UTILITY_CLASS_NAME = "$r8$EnumUnboxingUtility";
  private static final int REQUIRED_CLASS_FILE_VERSION = 52;

  private final AppView<AppInfoWithLiveness> appView;
  private final DexItemFactory factory;
  // TODO(b/150193407): Use enumInfoMap instead of Map<DexField, EnumValueInfo>.
  private final Map<DexType, Map<DexField, EnumValueInfo>> enumsToUnbox;

  private final DexType utilityClassType;
  private final DexMethod ordinalUtilityMethod;

  private boolean requiresOrdinalUtilityMethod = false;

  EnumUnboxingRewriter(AppView<AppInfoWithLiveness> appView, Set<DexType> enumsToUnbox) {
    this.appView = appView;
    this.factory = appView.dexItemFactory();
    ImmutableMap.Builder<DexType, Map<DexField, EnumValueInfo>> builder = ImmutableMap.builder();
    for (DexType toUnbox : enumsToUnbox) {
      builder.put(toUnbox, appView.appInfo().withLiveness().getEnumValueInfoMapFor(toUnbox));
    }
    this.enumsToUnbox = builder.build();

    this.utilityClassType = factory.createType("L" + ENUM_UNBOXING_UTILITY_CLASS_NAME + ";");
    this.ordinalUtilityMethod =
        factory.createMethod(
            utilityClassType,
            factory.createProto(factory.intType, factory.intType),
            "$enumboxing$ordinal");
  }

  void rewriteCode(IRCode code) {
    // We should not process the enum methods, they will be removed and they may contain invalid
    // rewriting rules.
    if (enumsToUnbox.isEmpty()) {
      return;
    }
    Set<Phi> affectedPhis = Sets.newIdentityHashSet();
    InstructionListIterator iterator = code.instructionListIterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      // Rewrites specific enum methods, such as ordinal, into their corresponding enum unboxed
      // counterpart.
      if (instruction.isInvokeMethodWithReceiver()) {
        InvokeMethodWithReceiver invokeMethod = instruction.asInvokeMethodWithReceiver();
        DexMethod invokedMethod = invokeMethod.getInvokedMethod();
        if (invokedMethod == factory.enumMethods.ordinal
            && invokeMethod.getReceiver().getTypeLattice().isInt()) {
          instruction =
              new InvokeStatic(
                  ordinalUtilityMethod, invokeMethod.outValue(), invokeMethod.inValues());
          iterator.replaceCurrentInstruction(instruction);
          requiresOrdinalUtilityMethod = true;
        }
        // TODO(b/147860220): rewrite also other enum methods.
      }
      // Rewrites direct access to enum values into the corresponding int, $VALUES is not
      // supported.
      if (instruction.isStaticGet()) {
        StaticGet staticGet = instruction.asStaticGet();
        DexType holder = staticGet.getField().holder;
        if (enumsToUnbox.containsKey(holder)) {
          if (staticGet.outValue() == null) {
            iterator.removeInstructionIgnoreOutValue();
            continue;
          }
          Map<DexField, EnumValueInfo> enumValueInfoMap = enumsToUnbox.get(holder);
          assert enumValueInfoMap != null;
          // Replace by ordinal + 1 for null check (null is 0).
          EnumValueInfo enumValueInfo = enumValueInfoMap.get(staticGet.getField());
          assert enumValueInfo != null
              : "Invalid read to " + staticGet.getField().name + ", error during enum analysis";
          instruction = new ConstNumber(staticGet.outValue(), enumValueInfo.ordinal + 1);
          staticGet
              .outValue()
              .setTypeLattice(PrimitiveTypeLatticeElement.fromNumericType(NumericType.INT));
          iterator.replaceCurrentInstruction(instruction);
          affectedPhis.addAll(staticGet.outValue().uniquePhiUsers());
        }
      }
      assert validateEnumToUnboxRemoved(instruction);
    }
    if (!affectedPhis.isEmpty()) {
      new DestructivePhiTypeUpdater(appView).recomputeAndPropagateTypes(code, affectedPhis);
      assert code.verifyTypes(appView);
    }
    assert code.isConsistentSSA();
  }

  private boolean validateEnumToUnboxRemoved(Instruction instruction) {
    if (instruction.outValue() == null) {
      return true;
    }
    TypeLatticeElement typeLattice = instruction.outValue().getTypeLattice();
    assert !typeLattice.isClassType()
        || !enumsToUnbox.containsKey(typeLattice.asClassTypeLatticeElement().getClassType());
    if (typeLattice.isArrayType()) {
      TypeLatticeElement arrayBaseTypeLattice =
          typeLattice.asArrayTypeLatticeElement().getArrayBaseTypeLattice();
      assert !arrayBaseTypeLattice.isClassType()
          || !enumsToUnbox.containsKey(
              arrayBaseTypeLattice.asClassTypeLatticeElement().getClassType());
    }
    return true;
  }

  // TODO(b/150172351): Synthesize the utility class upfront in the enqueuer.
  void synthesizeEnumUnboxingUtilityClass(
      DexApplication.Builder<?> appBuilder, IRConverter converter, ExecutorService executorService)
      throws ExecutionException {
    // Synthesize a class which holds various utility methods that may be called from the IR
    // rewriting. If any of these methods are not used, they will be removed by the Enqueuer.
    List<DexEncodedMethod> requiredMethods = new ArrayList<>();
    if (requiresOrdinalUtilityMethod) {
      requiredMethods.add(synthesizeOrdinalMethod());
    }
    // TODO(b/147860220): synthesize also other enum methods.
    if (requiredMethods.isEmpty()) {
      return;
    }
    DexProgramClass utilityClass =
        new DexProgramClass(
            utilityClassType,
            null,
            new SynthesizedOrigin("EnumUnboxing ", getClass()),
            ClassAccessFlags.fromSharedAccessFlags(Constants.ACC_PUBLIC | Constants.ACC_SYNTHETIC),
            factory.objectType,
            DexTypeList.empty(),
            factory.createString("enumunboxing"),
            null,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            DexAnnotationSet.empty(),
            DexEncodedField.EMPTY_ARRAY,
            DexEncodedField.EMPTY_ARRAY,
            // All synthesized methods are static in this case.
            requiredMethods.toArray(DexEncodedMethod.EMPTY_ARRAY),
            DexEncodedMethod.EMPTY_ARRAY,
            factory.getSkipNameValidationForTesting(),
            DexProgramClass::checksumFromType);
    appBuilder.addSynthesizedClass(utilityClass, utilityClassInMainDexList());
    appView.appInfo().addSynthesizedClass(utilityClass);
    converter.optimizeSynthesizedClass(utilityClass, executorService);
  }

  // TODO(b/150178516): Add a test for this case.
  private boolean utilityClassInMainDexList() {
    for (DexType toUnbox : enumsToUnbox.keySet()) {
      if (appView.appInfo().isInMainDexList(toUnbox)) {
        return true;
      }
    }
    return false;
  }

  private DexEncodedMethod synthesizeOrdinalMethod() {
    CfCode cfCode =
        EnumUnboxingCfMethods.EnumUnboxingMethods_ordinal(appView.options(), ordinalUtilityMethod);
    return new DexEncodedMethod(
        ordinalUtilityMethod,
        synthesizedMethodAccessFlags(),
        DexAnnotationSet.empty(),
        ParameterAnnotationsList.empty(),
        cfCode,
        REQUIRED_CLASS_FILE_VERSION,
        true);
  }

  private MethodAccessFlags synthesizedMethodAccessFlags() {
    return MethodAccessFlags.fromSharedAccessFlags(
        Constants.ACC_PUBLIC | Constants.ACC_SYNTHETIC | Constants.ACC_STATIC, false);
  }
}