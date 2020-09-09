// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.repackage;

import static com.android.tools.r8.shaking.ProguardConfigurationParser.FLATTEN_PACKAGE_HIERARCHY;
import static com.android.tools.r8.shaking.ProguardConfigurationParser.REPACKAGE_CLASSES;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.dexsplitter.SplitterTestBase.RunInterface;
import com.android.tools.r8.dexsplitter.SplitterTestBase.SplitRunner;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class RepackageWithFeatureSplitTest extends TestBase {

  private static final String REPACKAGE_DIR = "foo";

  private final String flattenPackageHierarchyOrRepackageClasses;
  private final TestParameters parameters;

  @Parameters(name = "{1}, kind: {0}")
  public static List<Object[]> data() {
    return buildParameters(
        ImmutableList.of(FLATTEN_PACKAGE_HIERARCHY, REPACKAGE_CLASSES),
        getTestParameters().withDexRuntimes().withAllApiLevels().build());
  }

  public RepackageWithFeatureSplitTest(
      String flattenPackageHierarchyOrRepackageClasses, TestParameters parameters) {
    this.flattenPackageHierarchyOrRepackageClasses = flattenPackageHierarchyOrRepackageClasses;
    this.parameters = parameters;
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addProgramClasses(BaseClass.class)
        .addFeatureSplit(FeatureMain.class, FeatureClass.class)
        .addFeatureSplitRuntime()
        .addKeepFeatureMainRule(FeatureMain.class)
        .addKeepRules(
            "-" + flattenPackageHierarchyOrRepackageClasses + " \"" + REPACKAGE_DIR + "\"")
        .addOptionsModification(
            options -> {
              assertFalse(options.testing.enableExperimentalRepackaging);
              options.testing.enableExperimentalRepackaging = true;
            })
        .enableInliningAnnotations()
        .setMinApi(parameters.getApiLevel())
        .compile()
        .inspect(this::inspectBase, this::inspectFeature)
        .runFeature(parameters.getRuntime(), FeatureMain.class)
        .assertSuccessWithOutputLines("Hello world!");
  }

  private void inspectBase(CodeInspector inspector) {
    assertEquals(4, inspector.allClasses().size());

    // The base classes added here.
    assertThat(inspector.clazz(BaseClass.class), isPresent());

    // The feature split runtime.
    assertThat(inspector.clazz(RunInterface.class), isPresent());
    assertThat(inspector.clazz(SplitRunner.class), isPresent());

    // TODO(b/168089304): Should be in the feature.
    assertThat(inspector.clazz(FeatureClass.class), isPresent());
  }

  private void inspectFeature(CodeInspector inspector) {
    assertEquals(1, inspector.allClasses().size());
    assertThat(inspector.clazz(FeatureMain.class), isPresent());
  }

  public static class BaseClass {

    @NeverInline
    public static void hello() {
      System.out.print("Hello");
    }
  }

  public static class FeatureMain implements RunInterface {

    @Override
    public void run() {
      BaseClass.hello();
      FeatureClass.world();
    }
  }

  public static class FeatureClass {

    @NeverInline
    public static void world() {
      System.out.println(" world!");
    }
  }
}