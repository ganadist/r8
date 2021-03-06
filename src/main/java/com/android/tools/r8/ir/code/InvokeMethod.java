// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;

import com.android.tools.r8.cf.LoadStoreHelper;
import com.android.tools.r8.cf.TypeVerificationHelper;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.LookupResult;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.ResolutionResult;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis;
import com.android.tools.r8.ir.analysis.fieldvalueanalysis.AbstractFieldSet;
import com.android.tools.r8.ir.analysis.modeling.LibraryMethodReadSetModeling;
import com.android.tools.r8.ir.analysis.type.ClassTypeElement;
import com.android.tools.r8.ir.analysis.type.TypeAnalysis;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.analysis.value.UnknownValue;
import com.android.tools.r8.ir.optimize.DefaultInliningOracle;
import com.android.tools.r8.ir.optimize.Inliner.InlineAction;
import com.android.tools.r8.ir.optimize.Inliner.Reason;
import com.android.tools.r8.ir.optimize.inliner.WhyAreYouNotInliningReporter;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import java.util.BitSet;
import java.util.List;

public abstract class InvokeMethod extends Invoke {

  private final DexMethod method;

  public InvokeMethod(DexMethod target, Value result, List<Value> arguments) {
    super(result, arguments);
    this.method = target;
  }

  public abstract boolean getInterfaceBit();

  @Override
  public DexType getReturnType() {
    return method.proto.returnType;
  }

  public DexMethod getInvokedMethod() {
    return method;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isInvokeMethod() && method == other.asInvokeMethod().getInvokedMethod();
  }

  @Override
  public String toString() {
    return super.toString() + "; method: " + method.toSourceString();
  }

  @Override
  public boolean isInvokeMethod() {
    return true;
  }

  @Override
  public InvokeMethod asInvokeMethod() {
    return this;
  }

  // In subclasses, e.g., invoke-virtual or invoke-super, use a narrower receiver type by using
  // receiver type and calling context---the holder of the method where the current invocation is.
  // TODO(b/140204899): Refactor lookup methods to be defined in a single place.
  public abstract DexEncodedMethod lookupSingleTarget(AppView<?> appView, ProgramMethod context);

  public final ProgramMethod lookupSingleProgramTarget(AppView<?> appView, ProgramMethod context) {
    DexEncodedMethod singleTarget = lookupSingleTarget(appView, context);
    return singleTarget != null ? singleTarget.asProgramMethod(appView) : null;
  }

  // TODO(b/140204899): Refactor lookup methods to be defined in a single place.
  public ProgramMethodSet lookupProgramDispatchTargets(
      AppView<AppInfoWithLiveness> appView, ProgramMethod context) {
    if (!getInvokedMethod().holder.isClassType()) {
      return null;
    }
    if (!isInvokeMethodWithDynamicDispatch()) {
      ProgramMethod singleTarget = lookupSingleProgramTarget(appView, context);
      return singleTarget != null ? ProgramMethodSet.create(singleTarget) : null;
    }
    DexProgramClass refinedReceiverUpperBound =
        asProgramClassOrNull(
            appView.definitionFor(
                TypeAnalysis.getRefinedReceiverType(appView, asInvokeMethodWithReceiver())));
    DexProgramClass refinedReceiverLowerBound = null;
    ClassTypeElement refinedReceiverLowerBoundType =
        asInvokeMethodWithReceiver().getReceiver().getDynamicLowerBoundType(appView);
    if (refinedReceiverLowerBoundType != null) {
      refinedReceiverLowerBound =
          asProgramClassOrNull(appView.definitionFor(refinedReceiverLowerBoundType.getClassType()));
      // TODO(b/154822960): Check if the lower bound is a subtype of the upper bound.
      if (refinedReceiverUpperBound != null
          && refinedReceiverLowerBound != null
          && !appView
              .appInfo()
              .isSubtype(refinedReceiverLowerBound.type, refinedReceiverUpperBound.type)) {
        refinedReceiverLowerBound = null;
      }
    }
    ResolutionResult resolutionResult = appView.appInfo().resolveMethod(method, getInterfaceBit());
    LookupResult lookupResult;
    if (refinedReceiverUpperBound != null) {
      lookupResult =
          resolutionResult.lookupVirtualDispatchTargets(
              context.getHolder(),
              appView.withLiveness().appInfo(),
              refinedReceiverUpperBound,
              refinedReceiverLowerBound);
    } else {
      lookupResult =
          resolutionResult.lookupVirtualDispatchTargets(
              context.getHolder(), appView.withLiveness().appInfo());
    }
    if (lookupResult.isLookupResultFailure()) {
      return null;
    }
    ProgramMethodSet result = ProgramMethodSet.create();
    lookupResult.forEach(
        methodTarget -> {
          if (methodTarget.isProgramMethod()) {
            result.add(methodTarget.asProgramMethod());
          }
        },
        lambda -> {
          // TODO(b/150277553): Support lambda targets.
        });
    return result;
  }

  public abstract InlineAction computeInlining(
      ProgramMethod singleTarget,
      Reason reason,
      DefaultInliningOracle decider,
      ClassInitializationAnalysis classInitializationAnalysis,
      WhyAreYouNotInliningReporter whyAreYouNotInliningReporter);

  @Override
  public boolean identicalAfterRegisterAllocation(Instruction other, RegisterAllocator allocator) {
    if (!super.identicalAfterRegisterAllocation(other, allocator)) {
      return false;
    }

    if (allocator.options().canHaveIncorrectJoinForArrayOfInterfacesBug()) {
      InvokeMethod invoke = other.asInvokeMethod();

      // If one of the arguments of this invoke is an array, then make sure that the corresponding
      // argument of the other invoke is the exact same value. Otherwise, the verifier may
      // incorrectly join the types of these arrays to Object[].
      for (int i = 0; i < arguments().size(); ++i) {
        Value argument = arguments().get(i);
        if (argument.getType().isArrayType() && argument != invoke.arguments().get(i)) {
          return false;
        }
      }
    }

    return true;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
    if (getReturnType().isVoidType()) {
      return;
    }
    if (outValue == null) {
      helper.popOutType(getReturnType(), this, it);
    } else {
      assert outValue.isUsed();
      helper.storeOutValue(this, it);
    }
  }

  @Override
  public boolean hasInvariantOutType() {
    return true;
  }

  @Override
  public DexType computeVerificationType(AppView<?> appView, TypeVerificationHelper helper) {
    return getReturnType();
  }

  @Override
  public boolean instructionMayTriggerMethodInvocation(AppView<?> appView, ProgramMethod context) {
    return true;
  }

  @Override
  public AbstractFieldSet readSet(AppView<AppInfoWithLiveness> appView, ProgramMethod context) {
    return LibraryMethodReadSetModeling.getModeledReadSetOrUnknown(this, appView.dexItemFactory());
  }

  @Override
  public AbstractValue getAbstractValue(
      AppView<AppInfoWithLiveness> appView, ProgramMethod context) {
    assert hasOutValue();
    DexEncodedMethod method = lookupSingleTarget(appView, context);
    if (method != null) {
      return method.getOptimizationInfo().getAbstractReturnValue();
    }
    return UnknownValue.getInstance();
  }

  boolean verifyD8LookupResult(
      DexEncodedMethod hierarchyResult, DexEncodedMethod lookupDirectTargetOnItself) {
    if (lookupDirectTargetOnItself == null) {
      return true;
    }
    assert lookupDirectTargetOnItself == hierarchyResult;
    return true;
  }

  @Override
  public boolean throwsNpeIfValueIsNull(Value value, AppView<?> appView, ProgramMethod context) {
    DexEncodedMethod singleTarget = lookupSingleTarget(appView, context);
    if (singleTarget != null) {
      BitSet nonNullParamOrThrow = singleTarget.getOptimizationInfo().getNonNullParamOrThrow();
      if (nonNullParamOrThrow != null) {
        int argumentIndex = inValues.indexOf(value);
        return argumentIndex >= 0 && nonNullParamOrThrow.get(argumentIndex);
      }
    }
    return false;
  }
}
