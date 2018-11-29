// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import static com.android.tools.r8.R8Command.USAGE_MESSAGE;

import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.dex.ApplicationWriter;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.dex.Marker.Tool;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.optimize.EnumOrdinalMapCollector;
import com.android.tools.r8.ir.optimize.SwitchMapCollector;
import com.android.tools.r8.jar.CfApplicationWriter;
import com.android.tools.r8.kotlin.Kotlin;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.Minifier;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.naming.ProguardMapApplier;
import com.android.tools.r8.naming.ProguardMapSupplier;
import com.android.tools.r8.naming.SeedMapper;
import com.android.tools.r8.naming.SourceFileRewriter;
import com.android.tools.r8.optimize.ClassAndMemberPublicizer;
import com.android.tools.r8.optimize.MemberRebindingAnalysis;
import com.android.tools.r8.optimize.VisibilityBridgeRemover;
import com.android.tools.r8.origin.CommandLineOrigin;
import com.android.tools.r8.shaking.AbstractMethodRemover;
import com.android.tools.r8.shaking.AnnotationRemover;
import com.android.tools.r8.shaking.DiscardedChecker;
import com.android.tools.r8.shaking.Enqueuer;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.shaking.MainDexListBuilder;
import com.android.tools.r8.shaking.ProguardClassFilter;
import com.android.tools.r8.shaking.ProguardConfiguration;
import com.android.tools.r8.shaking.ReasonPrinter;
import com.android.tools.r8.shaking.RootSetBuilder;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.shaking.TreePruner;
import com.android.tools.r8.shaking.VerticalClassMerger;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.CfgPrinter;
import com.android.tools.r8.utils.ExceptionUtils;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.InternalOptions.LineNumberOptimization;
import com.android.tools.r8.utils.LineNumberOptimizer;
import com.android.tools.r8.utils.Reporter;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.android.tools.r8.utils.VersionProperties;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * The R8 compiler.
 *
 * <p>R8 performs whole-program optimizing compilation of Java bytecode. It supports compilation of
 * Java bytecode to Java bytecode or DEX bytecode. R8 supports tree-shaking the program to remove
 * unneeded code and it supports minification of the program names to reduce the size of the
 * resulting program.
 *
 * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
 * API does not suffice please contact the D8/R8 team.
 *
 * <p>R8 supports some configuration using configuration files mostly compatible with the format of
 * the <a href="https://www.guardsquare.com/en/proguard">ProGuard</a> optimizer.
 *
 * <p>The compiler is invoked by calling {@link #run(R8Command) R8.run} with an appropriate {link
 * R8Command}. For example:
 *
 * <pre>
 *   R8.run(R8Command.builder()
 *       .addProgramFiles(inputPathA, inputPathB)
 *       .addLibraryFiles(androidJar)
 *       .setOutput(outputPath, OutputMode.DexIndexed)
 *       .build());
 * </pre>
 *
 * The above reads the input files denoted by {@code inputPathA} and {@code inputPathB}, compiles
 * them to DEX bytecode, using {@code androidJar} as the reference of the system runtime library,
 * and then writes the result to the directory or zip archive specified by {@code outputPath}.
 */
@Keep
public class R8 {

  private final Timing timing = new Timing("R8");
  private final InternalOptions options;

  private R8(InternalOptions options) {
    this.options = options;
    options.itemFactory.resetSortedIndices();
  }

  /**
   * Main API entry for the R8 compiler.
   *
   * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
   * API does not suffice please contact the R8 team.
   *
   * @param command R8 command.
   */
  public static void run(R8Command command) throws CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    runForTesting(app, options);
  }

  /**
   * Main API entry for the R8 compiler.
   *
   * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
   * API does not suffice please contact the R8 team.
   *
   * @param command R8 command.
   * @param executor executor service from which to get threads for multi-threaded processing.
   */
  public static void run(R8Command command, ExecutorService executor)
      throws CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExceptionUtils.withR8CompilationHandler(
        command.getReporter(),
        () -> {
          run(app, options, executor);
        });
  }

  // Compute the marker to be placed in the main dex file.
  private static Marker getMarker(InternalOptions options) {
    if (options.hasMarker()) {
      return options.getMarker();
    }
    Marker marker = new Marker(Tool.R8)
        .setVersion(Version.LABEL)
        .setCompilationMode(options.debug ? CompilationMode.DEBUG : CompilationMode.RELEASE)
        .setMinApi(options.minApiLevel);
    if (Version.isDev()) {
      marker.setSha1(VersionProperties.INSTANCE.getSha());
    }
    return marker;
  }

  static void writeApplication(
      ExecutorService executorService,
      DexApplication application,
      String deadCode,
      GraphLense graphLense,
      NamingLens namingLens,
      String proguardSeedsData,
      InternalOptions options,
      ProguardMapSupplier proguardMapSupplier)
      throws ExecutionException {
    try {
      Marker marker = getMarker(options);
      if (options.isGeneratingClassFiles()) {
        new CfApplicationWriter(
                application,
                options,
                deadCode,
                graphLense,
                namingLens,
                proguardSeedsData,
                proguardMapSupplier)
            .write(options.getClassFileConsumer(), executorService);
      } else {
        new ApplicationWriter(
                application,
                options,
                marker == null ? null : Collections.singletonList(marker),
                deadCode,
                graphLense,
                namingLens,
                proguardSeedsData,
                proguardMapSupplier)
            .write(executorService);
      }
    } catch (IOException e) {
      throw new RuntimeException("Cannot write application", e);
    }
  }

  private Set<DexType> filterMissingClasses(Set<DexType> missingClasses,
      ProguardClassFilter dontWarnPatterns) {
    Set<DexType> result = new HashSet<>(missingClasses);
    dontWarnPatterns.filterOutMatches(result);
    return result;
  }

  static void runForTesting(AndroidApp app, InternalOptions options)
      throws CompilationFailedException {
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    ExceptionUtils.withR8CompilationHandler(
        options.reporter,
        () -> {
          try {
            run(app, options, executor);
          } finally {
            executor.shutdown();
          }
        });
  }

  private static void run(AndroidApp app, InternalOptions options, ExecutorService executor)
      throws IOException {
    new R8(options).run(app, executor);
  }

  private void run(AndroidApp inputApp, ExecutorService executorService) throws IOException {
    assert options.programConsumer != null;
    if (options.quiet) {
      System.setOut(new PrintStream(ByteStreams.nullOutputStream()));
    }
    // TODO(b/65390962): Remove this warning once the CF backend is complete.
    if (options.isGeneratingClassFiles() && !options.testing.suppressExperimentalCfBackendWarning) {
      options.reporter.warning(new StringDiagnostic(
          "R8 support for generating Java classfiles is incomplete and experimental. "
              + "Even if R8 appears to succeed, the generated output is likely incorrect."));
    }
    try {
      AndroidApiLevel oLevel = AndroidApiLevel.O;
      if (options.minApiLevel >= oLevel.getLevel()
          && !options.mainDexKeepRules.isEmpty()) {
        throw new CompilationError("Automatic main dex list is not supported when compiling for "
            + oLevel.getName() + " and later (--min-api " + oLevel.getLevel() + ")");
      }
      DexApplication application =
          new ApplicationReader(inputApp, options, timing).read(executorService).toDirect();

      AppView<AppInfoWithSubtyping> appView =
          new AppView<>(new AppInfoWithSubtyping(application), GraphLense.getIdentityLense());
      RootSet rootSet;
      String proguardSeedsData = null;
      timing.begin("Strip unused code");
      try {
        Set<DexType> missingClasses = appView.getAppInfo().getMissingClasses();
        missingClasses = filterMissingClasses(
            missingClasses, options.proguardConfiguration.getDontWarnPatterns());
        if (!missingClasses.isEmpty()) {
          missingClasses.forEach(
              clazz -> {
                options.reporter.warning(
                    new StringDiagnostic("Missing class: " + clazz.toSourceString()));
              });
          if (!options.ignoreMissingClasses) {
            throw new CompilationError(
                "Compilation can't be completed because some library classes are missing.");
          }
        }

        // Compute kotlin info before setting the roots and before
        // kotlin metadata annotation is removed.
        computeKotlinInfoForProgramClasses(application, appView.getAppInfo());

        final ProguardConfiguration.Builder compatibility =
            ProguardConfiguration.builder(application.dexItemFactory, options.reporter);

        rootSet =
            new RootSetBuilder(
                    appView.getAppInfo(),
                    application,
                    options.proguardConfiguration.getRules(),
                    options)
                .run(executorService);

        Enqueuer enqueuer =
            new Enqueuer(
                appView.getAppInfo(),
                appView.getGraphLense(),
                options,
                options.forceProguardCompatibility,
                compatibility);
        appView.setAppInfo(enqueuer.traceApplication(rootSet, executorService, timing));
        if (options.proguardConfiguration.isPrintSeeds()) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          PrintStream out = new PrintStream(bytes);
          RootSetBuilder.writeSeeds(appView.getAppInfo().withLiveness(), out, type -> true);
          out.flush();
          proguardSeedsData = bytes.toString();
        }
        if (options.enableTreeShaking) {
          TreePruner pruner =
              new TreePruner(application, appView.getAppInfo().withLiveness(), options);
          application = pruner.run();
          // Recompute the subtyping information.
          appView.setAppInfo(
              appView
                  .getAppInfo()
                  .withLiveness()
                  .prunedCopyFrom(application, pruner.getRemovedClasses()));
          new AbstractMethodRemover(appView.getAppInfo()).run();
        }

        new AnnotationRemover(appView.getAppInfo().withLiveness(), options)
            .ensureValid(compatibility)
            .run();

        // TODO(69445518): This is still work in progress, and this file writing is currently used
        // for testing.
        if (options.forceProguardCompatibility
            && options.proguardCompatibilityRulesOutput != null) {
          try (Closer closer = Closer.create()) {
            OutputStream outputStream =
                FileUtils.openPath(
                    closer,
                    options.proguardCompatibilityRulesOutput,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            PrintStream ps = new PrintStream(outputStream);
            ps.println(compatibility.buildRaw().toString());
          }
        }
      } finally {
        timing.end();
      }

      if (options.proguardConfiguration.isAccessModificationAllowed()) {
        appView.setGraphLense(
            ClassAndMemberPublicizer.run(executorService, timing, application, appView, rootSet));
        // We can now remove visibility bridges. Note that we do not need to update the
        // invoke-targets here, as the existing invokes will simply dispatch to the now
        // visible super-method. MemberRebinding, if run, will then dispatch it correctly.
        application = new VisibilityBridgeRemover(appView.getAppInfo(), application).run();
      }

      if (appView.getAppInfo().hasLiveness()) {
        AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();

        if (options.proguardConfiguration.hasApplyMappingFile()) {
          SeedMapper seedMapper =
              SeedMapper.seedMapperFromFile(options.proguardConfiguration.getApplyMappingFile());
          timing.begin("apply-mapping");
          appView.setGraphLense(
              new ProguardMapApplier(appView.withLiveness(), seedMapper).run(timing));
          application = application.asDirect().rewrittenWithLense(appView.getGraphLense());
          appView.setAppInfo(
              appView
                  .getAppInfo()
                  .withLiveness()
                  .rewrittenWithLense(application.asDirect(), appView.getGraphLense()));
          timing.end();
        }
        appView.setGraphLense(new MemberRebindingAnalysis(appViewWithLiveness).run());
        if (options.enableVerticalClassMerging) {
          timing.begin("ClassMerger");
          VerticalClassMerger classMerger =
              new VerticalClassMerger(
                  application, appViewWithLiveness, executorService, options, timing);
          appView.setGraphLense(classMerger.run());
          timing.end();
          application = application.asDirect().rewrittenWithLense(appView.getGraphLense());
          appViewWithLiveness.setAppInfo(
              appViewWithLiveness
                  .getAppInfo()
                  .prunedCopyFrom(application, classMerger.getRemovedClasses())
                  .rewrittenWithLense(application.asDirect(), appView.getGraphLense()));
        }
        // Collect switch maps and ordinals maps.
        appViewWithLiveness.setAppInfo(new SwitchMapCollector(appViewWithLiveness, options).run());
        appViewWithLiveness.setAppInfo(
            new EnumOrdinalMapCollector(appViewWithLiveness, options).run());

        // TODO(b/79143143): re-enable once fixed.
        // graphLense = new BridgeMethodAnalysis(graphLense, appInfo.withLiveness()).run();
      }

      timing.begin("Create IR");
      Set<DexCallSite> desugaredCallSites;
      CfgPrinter printer = options.printCfg ? new CfgPrinter() : null;
      try {
        IRConverter converter =
            new IRConverter(
                appView.getAppInfo(), options, timing, printer, appView.getGraphLense());
        application = converter.optimize(application, executorService);
        appView.setGraphLense(converter.getGraphLense());
        desugaredCallSites = converter.getDesugaredCallSites();
      } finally {
        timing.end();
      }

      if (options.printCfg) {
        if (options.printCfgFile == null || options.printCfgFile.isEmpty()) {
          System.out.print(printer.toString());
        } else {
          try (OutputStreamWriter writer = new OutputStreamWriter(
              new FileOutputStream(options.printCfgFile),
              StandardCharsets.UTF_8)) {
            writer.write(printer.toString());
          }
        }
      }

      // Overwrite SourceFile if specified. This step should be done after IR conversion.
      timing.begin("Rename SourceFile");
      new SourceFileRewriter(appView.getAppInfo(), options).run();
      timing.end();

      if (!options.mainDexKeepRules.isEmpty()) {
        appView.setAppInfo(new AppInfoWithSubtyping(application));
        Enqueuer enqueuer =
            new Enqueuer(appView.getAppInfo(), appView.getGraphLense(), options, true);
        // Lets find classes which may have code executed before secondary dex files installation.
        RootSet mainDexRootSet =
            new RootSetBuilder(appView.getAppInfo(), application, options.mainDexKeepRules, options)
                .run(executorService);
        AppInfoWithLiveness mainDexAppInfo =
            enqueuer.traceMainDex(mainDexRootSet, executorService, timing);

        // LiveTypes is the result.
        Set<DexType> mainDexBaseClasses = new HashSet<>(mainDexAppInfo.liveTypes);

        // Calculate the automatic main dex list according to legacy multidex constraints.
        // Add those classes to an eventual manual list of classes.
        application = application.builder()
            .addToMainDexList(new MainDexListBuilder(mainDexBaseClasses, application).run())
            .build();
      }

      appView.setAppInfo(new AppInfoWithSubtyping(application));

      if (options.enableTreeShaking || options.enableMinification) {
        timing.begin("Post optimization code stripping");
        try {
          Enqueuer enqueuer =
              new Enqueuer(
                  appView.getAppInfo(),
                  appView.getGraphLense(),
                  options,
                  options.forceProguardCompatibility);
          appView.setAppInfo(enqueuer.traceApplication(rootSet, executorService, timing));

          AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();
          if (options.enableTreeShaking) {
            TreePruner pruner =
                new TreePruner(application, appViewWithLiveness.getAppInfo(), options);
            application = pruner.run();
            appViewWithLiveness.setAppInfo(
                appViewWithLiveness
                    .getAppInfo()
                    .prunedCopyFrom(application, pruner.getRemovedClasses()));
            // Print reasons on the application after pruning, so that we reflect the actual result.
            ReasonPrinter reasonPrinter = enqueuer.getReasonPrinter(rootSet.reasonAsked);
            reasonPrinter.run(application);
            // Remove annotations that refer to types that no longer exist.
            new AnnotationRemover(appView.getAppInfo().withLiveness(), options).run();
          }
        } finally {
          timing.end();
        }
      }

      // Only perform discard-checking if tree-shaking is turned on.
      if (options.enableTreeShaking && !rootSet.checkDiscarded.isEmpty()) {
        new DiscardedChecker(rootSet, application, options).run();
      }

      timing.begin("Minification");
      // If we did not have keep rules, everything will be marked as keep, so no minification
      // will happen. Just avoid the overhead.
      NamingLens namingLens =
          options.enableMinification
              ? new Minifier(
                      appView.getAppInfo().withLiveness(), rootSet, desugaredCallSites, options)
                  .run(timing)
              : NamingLens.getIdentityLens();
      timing.end();

      ProguardMapSupplier proguardMapSupplier;

      if (options.lineNumberOptimization != LineNumberOptimization.OFF) {
        timing.begin("Line number remapping");
        ClassNameMapper classNameMapper =
            LineNumberOptimizer.run(
                application,
                appView.getGraphLense(),
                namingLens,
                options.lineNumberOptimization == LineNumberOptimization.IDENTITY_MAPPING);
        timing.end();
        proguardMapSupplier =
            ProguardMapSupplier.fromClassNameMapper(classNameMapper, options.minApiLevel);
      } else {
        proguardMapSupplier =
            ProguardMapSupplier.fromNamingLens(namingLens, application, options.minApiLevel);
      }

      // If a method filter is present don't produce output since the application is likely partial.
      if (options.hasMethodsFilter()) {
        System.out.println("Finished compilation with method filter: ");
        options.methodsFilter.forEach((m) -> System.out.println("  - " + m));
        return;
      }

      // Generate the resulting application resources.
      writeApplication(
          executorService,
          application,
          application.deadCode,
          appView.getGraphLense(),
          namingLens,
          proguardSeedsData,
          options,
          proguardMapSupplier);

      options.printWarnings();
    } catch (ExecutionException e) {
      throw unwrapExecutionException(e);
    } finally {
      options.signalFinishedToConsumers();
      // Dump timings.
      if (options.printTimes) {
        timing.report();
      }
    }
  }

  private void computeKotlinInfoForProgramClasses(
      DexApplication application, AppInfoWithSubtyping appInfo) {
    Kotlin kotlin = appInfo.dexItemFactory.kotlin;
    Reporter reporter = options.reporter;
    for (DexProgramClass programClass : application.classes()) {
      programClass.setKotlinInfo(kotlin.getKotlinInfo(programClass, reporter));
    }
  }

  static RuntimeException unwrapExecutionException(ExecutionException executionException) {
    Throwable cause = executionException.getCause();
    if (cause instanceof CompilationError) {
      // add original exception as suppressed exception to provide the original stack trace
      cause.addSuppressed(executionException);
      throw (CompilationError) cause;
    } else if (cause instanceof RuntimeException) {
      cause.addSuppressed(executionException);
      throw (RuntimeException) cause;
    } else {
      throw new RuntimeException(executionException.getMessage(), cause);
    }
  }

  private static void run(String[] args) throws CompilationFailedException {
    R8Command command = R8Command.parse(args, CommandLineOrigin.INSTANCE).build();
    if (command.isPrintHelp()) {
      System.out.println(USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      Version.printToolVersion("R8");
      return;
    }
    InternalOptions options = command.getInternalOptions();
    ExecutorService executorService = ThreadUtils.getExecutorService(options);
    try {
      ExceptionUtils.withR8CompilationHandler(options.reporter, () ->
          run(command.getInputApp(), options, executorService));
    } finally {
      executorService.shutdown();
    }
  }

  /**
   * Command-line entry to R8.
   *
   * See {@link R8Command#USAGE_MESSAGE} or run {@code r8 --help} for usage information.
   */
  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println(USAGE_MESSAGE);
      System.exit(ExceptionUtils.STATUS_ERROR);
    }
    ExceptionUtils.withMainProgramHandler(() -> run(args));
  }
}
