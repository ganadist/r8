// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.MoveType;
import com.android.tools.r8.ir.code.SingleConstant;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.StringUtils;

public class ConstHigh16 extends Format21h implements SingleConstant {

  public static final int OPCODE = 0x15;
  public static final String NAME = "ConstHigh16";
  public static final String SMALI_NAME = "const/high16";

  /*package*/ ConstHigh16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public ConstHigh16(int register, int constantHighBits) {
    super(register, constantHighBits);
  }

  public String getName() {
    return NAME;
  }

  public String getSmaliName() {
    return SMALI_NAME;
  }

  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public int decodedValue() {
    return BBBB << 16;
  }

  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", 0x" + StringUtils.hexString(decodedValue(), 8) +
        " (" + decodedValue() + ")");
  }

  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AA + ", 0x" + StringUtils.hexString(decodedValue(), 8) +
        "  # " + decodedValue());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addConst(MoveType.SINGLE, AA, decodedValue());
  }
}
