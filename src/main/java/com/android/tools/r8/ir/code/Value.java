// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import static com.android.tools.r8.ir.analysis.type.Nullability.definitelyNotNull;
import static com.android.tools.r8.ir.code.Opcodes.ARGUMENT;
import static com.android.tools.r8.ir.code.Opcodes.CHECK_CAST;
import static com.android.tools.r8.ir.code.Opcodes.CONST_CLASS;
import static com.android.tools.r8.ir.code.Opcodes.CONST_NUMBER;
import static com.android.tools.r8.ir.code.Opcodes.CONST_STRING;
import static com.android.tools.r8.ir.code.Opcodes.DEX_ITEM_BASED_CONST_STRING;
import static com.android.tools.r8.ir.code.Opcodes.INSTANCE_OF;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.type.ClassTypeElement;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.analysis.value.UnknownValue;
import com.android.tools.r8.ir.optimize.DeadCodeRemover.DeadInstructionResult;
import com.android.tools.r8.ir.regalloc.LiveIntervals;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.MethodPosition;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.LongInterval;
import com.android.tools.r8.utils.Reporter;
import com.android.tools.r8.utils.SetUtils;
import com.android.tools.r8.utils.StringDiagnostic;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class Value implements Comparable<Value> {

  public void constrainType(
      ValueTypeConstraint constraint, DexMethod method, Origin origin, Reporter reporter) {
    TypeElement constrainedType = constrainedType(constraint);
    if (constrainedType == null) {
      throw reporter.fatalError(
          new StringDiagnostic(
              "Cannot constrain type: "
                  + type
                  + " for value: "
                  + this
                  + " by constraint: "
                  + constraint,
              origin,
              new MethodPosition(method.asMethodReference())));
    } else if (constrainedType != type) {
      setType(constrainedType);
    }
  }

  public TypeElement constrainedType(ValueTypeConstraint constraint) {
    if (constraint == ValueTypeConstraint.INT_OR_FLOAT_OR_OBJECT && !type.isWidePrimitive()) {
      return type;
    }
    switch (constraint) {
      case OBJECT:
        if (type.isTop()) {
          if (definition != null && definition.isConstNumber()) {
            assert definition.asConstNumber().isZero();
            return TypeElement.getNull();
          } else {
            return TypeElement.getBottom();
          }
        }
        if (type.isReferenceType()) {
          return type;
        }
        if (type.isBottom()) {
          // Only a few instructions may propagate a bottom input to a bottom output.
          assert isPhi()
              || definition.isDebugLocalWrite()
              || (definition.isArrayGet()
                  && definition.asArrayGet().getMemberType() == MemberType.OBJECT);
          return type;
        }
        break;
      case INT:
        if (type.isTop() || (type.isSinglePrimitive() && !type.isFloat())) {
          return TypeElement.getInt();
        }
        break;
      case FLOAT:
        if (type.isTop() || (type.isSinglePrimitive() && !type.isInt())) {
          return TypeElement.getFloat();
        }
        break;
      case INT_OR_FLOAT:
        if (type.isTop()) {
          return TypeElement.getSingle();
        }
        if (type.isSinglePrimitive()) {
          return type;
        }
        break;
      case LONG:
        if (type.isWidePrimitive() && !type.isDouble()) {
          return TypeElement.getLong();
        }
        break;
      case DOUBLE:
        if (type.isWidePrimitive() && !type.isLong()) {
          return TypeElement.getDouble();
        }
        break;
      case LONG_OR_DOUBLE:
        if (type.isWidePrimitive()) {
          return type;
        }
        break;
      default:
        throw new Unreachable("Unexpected constraint: " + constraint);
    }
    return null;
  }

  public boolean verifyCompatible(ValueType valueType) {
    return verifyCompatible(ValueTypeConstraint.fromValueType(valueType));
  }

  public boolean verifyCompatible(ValueTypeConstraint constraint) {
    assert constrainedType(constraint) != null;
    return true;
  }

  public void markNonDebugLocalRead() {
    assert !isPhi();
  }

  // Lazily allocated internal data for the debug information of locals.
  // This is wrapped in a class to avoid multiple pointers in the value structure.
  private static class DebugData {

    final DebugLocalInfo local;
    Map<Instruction, DebugUse> users = new HashMap<>();

    DebugData(DebugLocalInfo local) {
      this.local = local;
    }
  }

  // A debug-value user represents a point where the value is live, ends or starts.
  // If a point is marked as both ending and starting then it is simply live, but we maintain
  // the marker so as not to unintentionally end it if marked again.
  private enum DebugUse {
    LIVE, START, END, LIVE_FINAL;

    DebugUse start() {
      switch (this) {
        case LIVE:
        case START:
          return START;
        case END:
        case LIVE_FINAL:
          return LIVE_FINAL;
        default:
          throw new Unreachable();
      }
    }

    DebugUse end() {
      switch (this) {
        case LIVE:
        case END:
          return END;
        case START:
        case LIVE_FINAL:
          return LIVE_FINAL;
        default:
          throw new Unreachable();
      }
    }

    static DebugUse join(DebugUse a, DebugUse b) {
      if (a == LIVE_FINAL || b == LIVE_FINAL) {
        return LIVE_FINAL;
      }
      if (a == b) {
        return a;
      }
      if (a == LIVE) {
        return b;
      }
      if (b == LIVE) {
        return a;
      }
      assert (a == START && b == END) || (a == END && b == START);
      return LIVE_FINAL;
    }
  }

  public static final int UNDEFINED_NUMBER = -1;

  public static final Value UNDEFINED = new Value(UNDEFINED_NUMBER, TypeElement.getBottom(), null);

  protected final int number;
  public Instruction definition = null;
  private LinkedList<Instruction> users = new LinkedList<>();
  private Set<Instruction> uniqueUsers = null;
  private LinkedList<Phi> phiUsers = new LinkedList<>();
  private Set<Phi> uniquePhiUsers = null;
  private Value nextConsecutive = null;
  private Value previousConsecutive = null;
  private LiveIntervals liveIntervals;
  private int needsRegister = -1;
  private boolean isThis = false;
  private LongInterval valueRange;
  private DebugData debugData;
  protected TypeElement type;

  public Value(int number, TypeElement type, DebugLocalInfo local) {
    assert type != null;
    this.number = number;
    this.debugData = local == null ? null : new DebugData(local);
    this.type = type;
  }

  public boolean isFixedRegisterValue() {
    return false;
  }

  public FixedRegisterValue asFixedRegisterValue() {
    return null;
  }

  public Instruction getDefinition() {
    assert !isPhi();
    return definition;
  }

  public boolean hasAliasedValue() {
    return getAliasedValue() != this;
  }

  /**
   * If this value is defined by an instruction that defines an alias of another value, such as the
   * {@link Assume} instruction, then the incoming value to the {@link Assume} instruction is
   * returned (if the incoming value is not itself defined by an instruction that introduces an
   * alias).
   *
   * <p>If a phi value is found, then that phi value is returned.
   *
   * <p>This method is useful to find the "true" definition of a value inside the current method.
   */
  public Value getAliasedValue() {
    return getAliasedValue(
        DefaultAliasedValueConfiguration.getInstance(), Predicates.alwaysFalse());
  }

  public Value getAliasedValue(AliasedValueConfiguration configuration) {
    return getAliasedValue(configuration, Predicates.alwaysFalse());
  }

  public Value getAliasedValue(
      AliasedValueConfiguration configuration, Predicate<Value> stoppingCriterion) {
    assert stoppingCriterion != null;
    Set<Value> visited = Sets.newIdentityHashSet();
    Value lastAliasedValue;
    Value aliasedValue = this;
    do {
      lastAliasedValue = aliasedValue;
      if (aliasedValue.isPhi()) {
        return aliasedValue;
      }
      if (stoppingCriterion.test(aliasedValue)) {
        return aliasedValue;
      }
      Instruction definitionOfAliasedValue = aliasedValue.definition;
      if (configuration.isIntroducingAnAlias(definitionOfAliasedValue)) {
        aliasedValue = configuration.getAliasForOutValue(definitionOfAliasedValue);

        // There shouldn't be a cycle.
        assert visited.add(aliasedValue);
      }
    } while (aliasedValue != lastAliasedValue);
    assert aliasedValue.isPhi() || !aliasedValue.definition.isAssume();
    return aliasedValue;
  }

  public Value getSpecificAliasedValue(Predicate<Value> stoppingCriterion) {
    Value aliasedValue =
        getAliasedValue(DefaultAliasedValueConfiguration.getInstance(), stoppingCriterion);
    return stoppingCriterion.test(aliasedValue) ? aliasedValue : null;
  }

  public int getNumber() {
    return number;
  }

  public int requiredRegisters() {
    return type.requiredRegisters();
  }

  public DebugLocalInfo getLocalInfo() {
    return debugData == null ? null : debugData.local;
  }

  public boolean hasLocalInfo() {
    return getLocalInfo() != null;
  }

  public void setLocalInfo(DebugLocalInfo local) {
    assert local != null;
    assert debugData == null;
    debugData = new DebugData(local);
  }

  public void clearLocalInfo() {
    assert debugData.users.isEmpty();
    debugData = null;
  }

  public boolean hasSameOrNoLocal(Value other) {
    assert other != null;
    return hasLocalInfo()
        ? other.getLocalInfo() == this.getLocalInfo()
        : !other.hasLocalInfo();
  }

  public List<Instruction> getDebugLocalStarts() {
    if (debugData == null) {
      return Collections.emptyList();
    }
    List<Instruction> starts = new ArrayList<>(debugData.users.size());
    for (Entry<Instruction, DebugUse> entry : debugData.users.entrySet()) {
      if (entry.getValue() == DebugUse.START) {
        starts.add(entry.getKey());
      }
    }
    return starts;
  }

  public List<Instruction> getDebugLocalEnds() {
    if (debugData == null) {
      return Collections.emptyList();
    }
    List<Instruction> ends = new ArrayList<>(debugData.users.size());
    for (Entry<Instruction, DebugUse> entry : debugData.users.entrySet()) {
      if (entry.getValue() == DebugUse.END) {
        ends.add(entry.getKey());
      }
    }
    return ends;
  }

  public void addDebugLocalStart(Instruction start) {
    assert start != null;
    debugData.users.put(start, markStart(debugData.users.get(start)));
  }

  private DebugUse markStart(DebugUse use) {
    assert use != null;
    return use == null ? DebugUse.START : use.start();
  }

  public void addDebugLocalEnd(Instruction end) {
    assert end != null;
    debugData.users.put(end, markEnd(debugData.users.get(end)));
  }

  private DebugUse markEnd(DebugUse use) {
    assert use != null;
    return use == null ? DebugUse.END : use.end();
  }

  public void linkTo(Value other) {
    assert nextConsecutive == null || nextConsecutive == other;
    assert other.previousConsecutive == null || other.previousConsecutive == this;
    other.previousConsecutive = this;
    nextConsecutive = other;
  }

  public void replaceLink(Value newArgument) {
    assert isLinked();
    if (previousConsecutive != null) {
      previousConsecutive.nextConsecutive = newArgument;
      newArgument.previousConsecutive = previousConsecutive;
      previousConsecutive = null;
    }
    if (nextConsecutive != null) {
      nextConsecutive.previousConsecutive = newArgument;
      newArgument.nextConsecutive = nextConsecutive;
      nextConsecutive = null;
    }
  }

  public boolean isLinked() {
    return nextConsecutive != null || previousConsecutive != null;
  }

  public Value getStartOfConsecutive() {
    Value current = this;
    while (current.getPreviousConsecutive() != null) {
      current = current.getPreviousConsecutive();
    }
    return current;
  }

  public Value getNextConsecutive() {
    return nextConsecutive;
  }

  public Value getPreviousConsecutive() {
    return previousConsecutive;
  }

  public boolean onlyUsedInBlock(BasicBlock block) {
    for (Instruction user : uniqueUsers()) {
      if (user.getBlock() != block) {
        return false;
      }
    }
    return true;
  }

  public Set<Instruction> uniqueUsers() {
    if (uniqueUsers != null) {
      return uniqueUsers;
    }
    return uniqueUsers = ImmutableSet.copyOf(users);
  }

  public boolean hasSingleUniqueUser() {
    return uniqueUsers().size() == 1;
  }

  public Instruction singleUniqueUser() {
    assert ImmutableSet.copyOf(users).size() == 1;
    return users.getFirst();
  }

  public Set<Instruction> aliasedUsers() {
    return aliasedUsers(DefaultAliasedValueConfiguration.getInstance());
  }

  public Set<Instruction> aliasedUsers(AliasedValueConfiguration configuration) {
    Set<Instruction> users = SetUtils.newIdentityHashSet(uniqueUsers());
    Set<Instruction> visited = Sets.newIdentityHashSet();
    collectAliasedUsersViaAssume(configuration, visited, uniqueUsers(), users);
    return users;
  }

  private static void collectAliasedUsersViaAssume(
      AliasedValueConfiguration configuration,
      Set<Instruction> visited,
      Set<Instruction> usersToTest,
      Set<Instruction> collectedUsers) {
    for (Instruction user : usersToTest) {
      if (!visited.add(user)) {
        continue;
      }
      if (configuration.isIntroducingAnAlias(user)) {
        collectedUsers.addAll(user.outValue().uniqueUsers());
        collectAliasedUsersViaAssume(
            configuration, visited, user.outValue().uniqueUsers(), collectedUsers);
      }
    }
  }

  public Phi firstPhiUser() {
    assert !phiUsers.isEmpty();
    return phiUsers.getFirst();
  }

  public Set<Phi> uniquePhiUsers() {
    if (uniquePhiUsers != null) {
      return uniquePhiUsers;
    }
    return uniquePhiUsers = ImmutableSet.copyOf(phiUsers);
  }

  public Set<Instruction> debugUsers() {
    return debugData == null ? null : Collections.unmodifiableSet(debugData.users.keySet());
  }

  public boolean hasAnyUsers() {
    return hasUsers() || hasPhiUsers() || hasDebugUsers();
  }

  public boolean hasDebugUsers() {
    return debugData != null && !debugData.users.isEmpty();
  }

  public boolean hasPhiUsers() {
    return !phiUsers.isEmpty();
  }

  public boolean hasUsers() {
    return !users.isEmpty();
  }

  public boolean hasUserThatMatches(Predicate<Instruction> predicate) {
    for (Instruction user : uniqueUsers()) {
      if (predicate.test(user)) {
        return true;
      }
    }
    return false;
  }

  public int numberOfUsers() {
    int size = users.size();
    if (size <= 1) {
      return size;
    }
    return uniqueUsers().size();
  }

  public int numberOfPhiUsers() {
    int size = phiUsers.size();
    if (size <= 1) {
      return size;
    }
    return uniquePhiUsers().size();
  }

  public int numberOfAllNonDebugUsers() {
    return numberOfUsers() + numberOfPhiUsers();
  }

  public int numberOfDebugUsers() {
    return debugData == null ? 0 : debugData.users.size();
  }

  public int numberOfAllUsers() {
    return numberOfAllNonDebugUsers() + numberOfDebugUsers();
  }

  public boolean isUsed() {
    return !users.isEmpty() || !phiUsers.isEmpty() || numberOfDebugUsers() > 0;
  }

  public boolean isAlwaysNull(AppView<?> appView) {
    if (hasLocalInfo()) {
      // Not always null as the value can be changed via the debugger.
      return false;
    }
    if (type.isDefinitelyNull()) {
      return true;
    }
    if (type.isClassType() && appView.appInfo().hasLiveness()) {
      return type.asClassType().getClassType().isAlwaysNull(appView.withLiveness());
    }
    return false;
  }

  public boolean usedInMonitorOperation() {
    for (Instruction instruction : uniqueUsers()) {
      if (instruction.isMonitor()) {
        return true;
      }
    }
    return false;
  }

  public void addUser(Instruction user) {
    users.add(user);
    uniqueUsers = null;
  }

  public void removeUser(Instruction user) {
    users.remove(user);
    uniqueUsers = null;
  }

  private void fullyRemoveUser(Instruction user) {
    users.removeIf(u -> u == user);
    uniqueUsers = null;
  }

  public void clearUsers() {
    users.clear();
    uniqueUsers = null;
    clearPhiUsers();
    if (debugData != null) {
      debugData.users.clear();
    }
  }

  public void clearPhiUsers() {
    phiUsers.clear();
    uniquePhiUsers = null;
  }

  public void addPhiUser(Phi user) {
    phiUsers.add(user);
    uniquePhiUsers = null;
  }

  public void removePhiUser(Phi user) {
    phiUsers.remove(user);
    uniquePhiUsers = null;
  }

  private void fullyRemovePhiUser(Phi user) {
    phiUsers.removeIf(u -> u == user);
    uniquePhiUsers = null;
  }

  public void addDebugUser(Instruction user) {
    assert hasLocalInfo();
    debugData.users.putIfAbsent(user, DebugUse.LIVE);
  }

  public boolean isUninitializedLocal() {
    return definition != null && definition.isDebugLocalUninitialized();
  }

  public boolean isInitializedLocal() {
    return !isUninitializedLocal();
  }

  public void removeDebugUser(Instruction user) {
    if (debugData != null && debugData.users != null) {
      debugData.users.remove(user);
      return;
    }
    assert false;
  }

  public boolean hasUsersInfo() {
    return users != null;
  }

  public void clearUsersInfo() {
    users = null;
    uniqueUsers = null;
    phiUsers = null;
    uniquePhiUsers = null;
    if (debugData != null) {
      debugData.users = null;
    }
  }

  // Returns the set of Value that are affected if the current value's type lattice is updated.
  public Set<Value> affectedValues() {
    ImmutableSet.Builder<Value> affectedValues = ImmutableSet.builder();
    forEachAffectedValue(affectedValues::add);
    return affectedValues.build();
  }

  public void addAffectedValuesTo(Set<Value> affectedValues) {
    forEachAffectedValue(affectedValues::add);
  }

  public void forEachAffectedValue(Consumer<Value> consumer) {
    for (Instruction user : uniqueUsers()) {
      if (user.hasOutValue()) {
        consumer.accept(user.outValue());
      }
    }
    uniquePhiUsers().forEach(consumer::accept);
  }

  public void replaceUsers(Value newValue) {
    if (this == newValue) {
      return;
    }
    for (Instruction user : uniqueUsers()) {
      user.replaceValue(this, newValue);
    }
    for (Phi user : uniquePhiUsers()) {
      user.replaceOperand(this, newValue);
    }
    if (debugData != null) {
      for (Entry<Instruction, DebugUse> user : debugData.users.entrySet()) {
        replaceUserInDebugData(user, newValue);
      }
      debugData.users.clear();
    }
    clearUsers();
  }

  public void replacePhiUsers(Value newValue) {
    if (this == newValue) {
      return;
    }
    for (Phi user : uniquePhiUsers()) {
      user.replaceOperand(this, newValue);
    }
    clearPhiUsers();
  }

  public void replaceSelectiveInstructionUsers(Value newValue, Predicate<Instruction> predicate) {
    if (this == newValue) {
      return;
    }
    for (Instruction user : uniqueUsers()) {
      if (predicate.test(user)) {
        fullyRemoveUser(user);
        user.replaceValue(this, newValue);
      }
    }
  }

  public void replaceSelectiveUsers(
      Value newValue,
      Set<Instruction> selectedInstructions,
      Map<Phi, IntList> selectedPhisWithPredecessorIndexes) {
    if (this == newValue) {
      return;
    }
    // Unlike {@link #replaceUsers} above, which clears all users at the end, this routine will
    // manually remove updated users. Remove such updated users from the user pool before replacing
    // value, otherwise we lost the identity.
    for (Instruction user : uniqueUsers()) {
      if (selectedInstructions.contains(user)) {
        fullyRemoveUser(user);
        user.replaceValue(this, newValue);
      }
    }
    Set<Phi> selectedPhis = selectedPhisWithPredecessorIndexes.keySet();
    for (Phi user : uniquePhiUsers()) {
      if (selectedPhis.contains(user)) {
        long count = user.getOperands().stream().filter(operand -> operand == this).count();
        IntList positionsToUpdate = selectedPhisWithPredecessorIndexes.get(user);
        // We may not _fully_ remove this from the phi, e.g., phi(v0, v1, v1) -> phi(v0, vn, v1).
        if (count == positionsToUpdate.size()) {
          fullyRemovePhiUser(user);
        }
        for (int position : positionsToUpdate) {
          assert user.getOperand(position) == this;
          user.replaceOperandAt(position, newValue);
        }
      }
    }
    if (debugData != null) {
      Iterator<Entry<Instruction, DebugUse>> users = debugData.users.entrySet().iterator();
      while (users.hasNext()) {
        Entry<Instruction, DebugUse> user = users.next();
        if (selectedInstructions.contains(user.getKey())) {
          replaceUserInDebugData(user, newValue);
          users.remove();
        }
      }
    }
  }

  private void replaceUserInDebugData(Entry<Instruction, DebugUse> user, Value newValue) {
    Instruction instruction = user.getKey();
    DebugUse debugUse = user.getValue();
    instruction.replaceDebugValue(this, newValue);
    // If user is a DebugLocalRead and now has no debug values, we would like to remove it.
    // However, replaceUserInDebugData() is called in contexts where the instruction list is being
    // iterated, so we cannot remove user from the instruction list at this point.
    if (newValue.hasLocalInfo()) {
      DebugUse existing = newValue.debugData.users.get(instruction);
      assert existing != null;
      newValue.debugData.users.put(instruction, DebugUse.join(debugUse, existing));
    }
  }

  public void replaceDebugUser(Instruction oldUser, Instruction newUser) {
    DebugUse use = debugData.users.remove(oldUser);
    if (use == DebugUse.START && newUser.outValue == this) {
      // Register allocation requires that debug values are live at the entry to the instruction.
      // Remove this debug use since it is starting at the instruction that defines it.
      return;
    }
    if (use != null) {
      newUser.addDebugValue(this);
      debugData.users.put(newUser, use);
    }
  }

  public void setLiveIntervals(LiveIntervals intervals) {
    assert liveIntervals == null;
    liveIntervals = intervals;
  }

  public LiveIntervals getLiveIntervals() {
    return liveIntervals;
  }

  public boolean needsRegister() {
    assert needsRegister >= 0;
    assert !hasUsersInfo() || (needsRegister > 0) == internalComputeNeedsRegister();
    return needsRegister > 0;
  }

  public void setNeedsRegister(boolean value) {
    assert needsRegister == -1 || (needsRegister > 0) == value;
    needsRegister = value ? 1 : 0;
  }

  public void computeNeedsRegister() {
    assert needsRegister < 0;
    setNeedsRegister(internalComputeNeedsRegister());
  }

  public boolean internalComputeNeedsRegister() {
    if (!isConstNumber()) {
      return true;
    }
    if (numberOfPhiUsers() > 0) {
      return true;
    }
    for (Instruction user : uniqueUsers()) {
      if (user.needsValueInRegister(this)) {
        return true;
      }
    }
    return false;
  }

  public boolean hasRegisterConstraint() {
    for (Instruction instruction : uniqueUsers()) {
      if (instruction.maxInValueRegister() != Constants.U16BIT_MAX) {
        return true;
      }
    }
    return false;
  }

  public boolean isValueOnStack() {
    return false;
  }

  @Override
  public int compareTo(Value value) {
    return Integer.compare(this.number, value.number);
  }

  @Override
  public int hashCode() {
    return number;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("v");
    builder.append(number);
    boolean isConstant = definition != null && definition.isConstNumber();
    if (isConstant || hasLocalInfo()) {
      builder.append("(");
      if (isConstant && definition.asConstNumber().outValue != null) {
        ConstNumber constNumber = definition.asConstNumber();
        if (constNumber.getOutType().isSinglePrimitive()) {
          builder.append((int) constNumber.getRawValue());
        } else {
          builder.append(constNumber.getRawValue());
        }
      }
      if (isConstant && hasLocalInfo()) {
        builder.append(", ");
      }
      if (hasLocalInfo()) {
        builder.append(getLocalInfo());
      }
      builder.append(")");
    }
    if (valueRange != null) {
      builder.append(valueRange);
    }
    return builder.toString();
  }

  public ValueType outType() {
    return ValueType.fromType(type);
  }

  public ConstInstruction getConstInstruction() {
    assert isConstant();
    return definition.getOutConstantConstInstruction();
  }

  public boolean isConstNumber() {
    return isConstant() && getConstInstruction().isConstNumber();
  }

  public boolean isConstZero() {
    return isConstNumber() && definition.asConstNumber().isZero();
  }

  public boolean isConstString() {
    return isConstant() && getConstInstruction().isConstString();
  }

  public boolean isDexItemBasedConstString() {
    return isConstant() && getConstInstruction().isDexItemBasedConstString();
  }

  public boolean isDexItemBasedConstStringThatNeedsToComputeClassName() {
    return isDexItemBasedConstString()
        && getConstInstruction()
            .asDexItemBasedConstString()
            .getNameComputationInfo()
            .needsToComputeName();
  }

  public boolean isConstClass() {
    return isConstant() && getConstInstruction().isConstClass();
  }

  public boolean isConstant() {
    return definition.isOutConstant() && !hasLocalInfo();
  }

  public AbstractValue getAbstractValue(AppView<?> appView, ProgramMethod context) {
    if (!appView.enableWholeProgramOptimizations()) {
      return UnknownValue.getInstance();
    }

    Value root = getAliasedValue();
    if (root.isPhi()) {
      return UnknownValue.getInstance();
    }

    return root.definition.getAbstractValue(appView.withLiveness(), context);
  }

  public boolean isDefinedByInstructionSatisfying(Predicate<Instruction> predicate) {
    return predicate.test(definition);
  }

  public boolean isPhi() {
    return false;
  }

  public Phi asPhi() {
    return null;
  }

  /**
   * Returns whether this value is known to never be <code>null</code>.
   */
  public boolean isNeverNull() {
    assert type.isReferenceType();
    return (definition != null && definition.isAssumeNonNull())
        || type.nullability().isDefinitelyNotNull();
  }

  public boolean isArgument() {
    return isDefinedByInstructionSatisfying(Instruction::isArgument);
  }

  public boolean onlyDependsOnArgument() {
    if (isPhi()) {
      return false;
    }
    switch (definition.opcode()) {
      case ARGUMENT:
      case CONST_CLASS:
      case CONST_NUMBER:
      case CONST_STRING:
      case DEX_ITEM_BASED_CONST_STRING:
        // Constants don't depend on anything.
        return true;
      case CHECK_CAST:
        return definition.asCheckCast().object().onlyDependsOnArgument();
      case INSTANCE_OF:
        return definition.asInstanceOf().value().onlyDependsOnArgument();
      default:
        return false;
    }
  }

  public boolean knownToBeBoolean() {
    return knownToBeBoolean(null);
  }

  public boolean knownToBeBoolean(Set<Phi> seen) {
    if (!getType().isInt()) {
      return false;
    }

    if (isPhi()) {
      Phi self = this.asPhi();
      if (seen == null) {
        seen = Sets.newIdentityHashSet();
      }
      if (seen.contains(self)) {
        return true;
      }
      seen.add(self);
      for (Value operand : self.getOperands()) {
        if (!operand.knownToBeBoolean(seen)) {
          operand.knownToBeBoolean(seen);
          return false;
        }
      }
      return true;
    }
    assert definition != null;
    return definition.outTypeKnownToBeBoolean(seen);
  }

  public void markAsThis() {
    assert isArgument();
    assert !isThis;
    isThis = true;
  }

  /**
   * Returns whether this value is known to be the receiver (this argument) in a method body.
   * <p>
   * For a receiver value {@link #isNeverNull()} is guaranteed to be <code>true</code> as well.
   */
  public boolean isThis() {
    return isThis;
  }

  public void setValueRange(LongInterval range) {
    valueRange = range;
  }

  public boolean hasValueRange() {
    return valueRange != null || isConstNumber();
  }

  public boolean isValueInRange(int value) {
    if (isConstNumber()) {
      return value == getConstInstruction().asConstNumber().getIntValue();
    } else {
      return valueRange != null && valueRange.containsValue(value);
    }
  }

  public LongInterval getValueRange() {
    if (isConstNumber()) {
      if (type.isSinglePrimitive()) {
        int value = getConstInstruction().asConstNumber().getIntValue();
        return new LongInterval(value, value);
      } else {
        assert type.isWidePrimitive();
        long value = getConstInstruction().asConstNumber().getLongValue();
        return new LongInterval(value, value);
      }
    } else {
      return valueRange;
    }
  }

  public boolean isDead(AppView<?> appView, IRCode code) {
    // Totally unused values are trivially dead.
    return !isUsed() || isDead(appView, code, Predicates.alwaysFalse());
  }

  public boolean isDead(AppView<?> appView, IRCode code, Predicate<Instruction> ignoreUser) {
    // Totally unused values are trivially dead.
    return !isUsed() || isDead(appView, code, ignoreUser, Sets.newIdentityHashSet());
  }

  /**
   * Used to determine if a given value is dead.
   *
   * <p>The predicate `ignoreUser` can be used to determine if a given value is dead under the
   * assumption that the instructions for which `ignoreUser` returns true are also dead.
   *
   * <p>One use case of this is when we attempt to determine if a call to {@code <init>()} can be
   * removed: calls to {@code <init>()} can only be removed if the receiver is dead except for the
   * constructor call.
   */
  protected boolean isDead(
      AppView<?> appView, IRCode code, Predicate<Instruction> ignoreUser, Set<Value> active) {
    // Give up when the dependent set of values reach a given threshold (otherwise this fails with
    // a StackOverflowError on Art003_omnibus_opcodesTest).
    if (active.size() > 100) {
      return false;
    }

    // If the value has debug users we cannot eliminate it since it represents a value in a local
    // variable that should be visible in the debugger.
    if (hasDebugUsers()) {
      return false;
    }
    // This is a candidate for a dead value. Guard against looping by adding it to the set of
    // currently active values.
    active.add(this);
    for (Instruction instruction : uniqueUsers()) {
      if (ignoreUser.test(instruction)) {
        continue;
      }
      DeadInstructionResult result = instruction.canBeDeadCode(appView, code);
      if (result.isNotDead()) {
        return false;
      }
      if (result.isMaybeDead()) {
        for (Value valueRequiredToBeDead : result.getValuesRequiredToBeDead()) {
          if (!active.contains(valueRequiredToBeDead)
              && !valueRequiredToBeDead.isDead(appView, code, ignoreUser, active)) {
            return false;
          }
        }
      }
      Value outValue = instruction.outValue();
      if (outValue != null
          && !active.contains(outValue)
          && !outValue.isDead(appView, code, ignoreUser, active)) {
        return false;
      }
    }
    for (Phi phi : uniquePhiUsers()) {
      if (!active.contains(phi) && !phi.isDead(appView, code, ignoreUser, active)) {
        return false;
      }
    }
    return true;
  }

  public boolean isZero() {
    return isConstant()
        && getConstInstruction().isConstNumber()
        && getConstInstruction().asConstNumber().isZero();
  }

  /**
   * Overwrites the current type lattice value without any assertions.
   *
   * @param newType The new type lattice element
   */
  public void setType(TypeElement newType) {
    assert newType != null;
    type = newType;
  }

  public void widening(AppView<?> appView, TypeElement newType) {
    // During WIDENING (due to fix-point iteration), type update is monotonically upwards,
    //   i.e., towards something wider.
    assert this.type.lessThanOrEqual(newType, appView)
        : "During WIDENING, "
            + newType
            + " < "
            + type
            + " at "
            + (isPhi() ? asPhi().printPhi() : definition.toString());
    setType(newType);
  }

  public void narrowing(AppView<?> appView, TypeElement newType) {
    // During NARROWING (e.g., after inlining), type update is monotonically downwards,
    //   i.e., towards something narrower, with more specific type info.
    assert (!appView.options().testing.enableNarrowingChecksInD8
                && !appView.enableWholeProgramOptimizations())
            || !this.type.strictlyLessThan(newType, appView)
        : "During NARROWING, "
            + type
            + " < "
            + newType
            + " at "
            + (isPhi() ? asPhi().printPhi() : definition.toString());
    setType(newType);
  }

  public BasicBlock getBlock() {
    return definition.getBlock();
  }

  public TypeElement getType() {
    return type;
  }

  public TypeElement getDynamicUpperBoundType(
      AppView<? extends AppInfoWithClassHierarchy> appView) {
    Value root = getAliasedValue();
    if (root.isPhi()) {
      assert getSpecificAliasedValue(
              value -> !value.isPhi() && value.definition.isAssumeDynamicType())
          == null;
      return root.getDynamicUpperBoundType(appView);
    }

    // Try to find an alias of the receiver, which is defined by an instruction of the type Assume.
    Value aliasedValue =
        getSpecificAliasedValue(value -> !value.isPhi() && value.definition.isAssumeDynamicType());
    TypeElement lattice;
    if (aliasedValue != null) {
      // If there is an alias of the receiver, which is defined by an Assume instruction that
      // carries a dynamic type, then use the dynamic type as the refined receiver type.
      lattice =
          aliasedValue.definition.asAssume().getDynamicTypeAssumption().getDynamicUpperBoundType();

      // For precision, verify that the dynamic type is at least as precise as the static type.
      assert lattice.lessThanOrEqualUpToNullability(type, appView) : type + " < " + lattice;
    } else {
      // Otherwise, simply use the static type.
      lattice = type;
    }

    // Account for nullability, which could be flown from non-null assumption in between dynamic
    // type assumption or simply from array/object creation.
    if (type.isDefinitelyNotNull() && lattice.isNullable()) {
      // Having non-null assumption means it is a reference type.
      assert lattice.isReferenceType();
      // Then, we can return the non-null variant of dynamic type if both assumptions are aliased.
      return lattice.asReferenceType().asMeetWithNotNull();
    }
    return lattice;
  }

  public ClassTypeElement getDynamicLowerBoundType(AppView<AppInfoWithLiveness> appView) {
    // If it is a final or effectively-final class type, then we know the lower bound.
    if (getType().isClassType()) {
      ClassTypeElement classType = getType().asClassType();
      DexType type = classType.getClassType();
      DexClass clazz = appView.definitionFor(type);
      if (clazz != null && clazz.isEffectivelyFinal(appView)) {
        return classType;
      }
    }

    Value root = getAliasedValue();
    if (root.isPhi()) {
      return null;
    }

    Instruction definition = root.definition;
    if (definition.isNewInstance()) {
      DexType type = definition.asNewInstance().clazz;
      DexClass clazz = appView.definitionFor(type);
      if (clazz != null && !clazz.isInterface()) {
        return ClassTypeElement.create(type, definitelyNotNull(), appView);
      }
      return null;
    }

    // Try to find an alias of the receiver, which is defined by an instruction of the type Assume.
    Value aliasedValue = getSpecificAliasedValue(value -> value.definition.isAssumeDynamicType());
    if (aliasedValue != null) {
      ClassTypeElement lattice =
          aliasedValue.definition.asAssume().getDynamicTypeAssumption().getDynamicLowerBoundType();
      return lattice != null && type.isDefinitelyNotNull() && lattice.isNullable()
          ? lattice.asMeetWithNotNull()
          : lattice;
    }

    return null;
  }
}
