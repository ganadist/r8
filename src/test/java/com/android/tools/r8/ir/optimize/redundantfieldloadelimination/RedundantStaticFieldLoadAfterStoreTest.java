// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.redundantfieldloadelimination;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.FieldSubject;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class RedundantStaticFieldLoadAfterStoreTest extends TestBase {

  private final TestParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimes().build();
  }

  public RedundantStaticFieldLoadAfterStoreTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(RedundantStaticFieldLoadAfterStoreTest.class)
        .addKeepMainRule(TestClass.class)
        .setMinApi(parameters.getRuntime())
        .compile()
        .inspect(
            inspector -> {
              ClassSubject classSubject = inspector.clazz(TestClass.class);
              assertThat(classSubject, isPresent());

              // Removing the actual field definition would require two optimization passes, because
              // we would need another round of tree shaking to learn that the `greeting` field is
              // no longer read after the field load in main() has been eliminated.
              FieldSubject fieldSubject = classSubject.uniqueFieldWithName("greeting");
              assertThat(fieldSubject, isPresent());

              MethodSubject methodSubject = classSubject.mainMethod();
              assertThat(methodSubject, isPresent());
              assertTrue(
                  methodSubject
                      .streamInstructions()
                      .filter(InstructionSubject::isStaticGet)
                      .map(InstructionSubject::getField)
                      .allMatch(field -> field.name.toSourceString().equals("out")));
            })
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutputLines("Hello world!");
  }

  static class TestClass {

    static String greeting;

    public static void main(String[] args) {
      greeting = "Hello world!";
      String str = greeting;
      System.out.println(str);
    }
  }
}
