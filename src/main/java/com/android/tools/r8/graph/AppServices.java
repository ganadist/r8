// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.DataDirectoryResource;
import com.android.tools.r8.DataEntryResource;
import com.android.tools.r8.DataResourceProvider;
import com.android.tools.r8.DataResourceProvider.Visitor;
import com.android.tools.r8.FeatureSplit;
import com.android.tools.r8.ProgramResourceProvider;
import com.android.tools.r8.ResourceException;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;

/** A description of the services and their implementations found in META-INF/services/. */
public class AppServices {

  public static final String SERVICE_DIRECTORY_NAME = "META-INF/services/";

  private final AppView<?> appView;

  // Mapping from service types to service implementation types.
  private final Map<DexType, Map<FeatureSplit, List<DexType>>> services;

  private AppServices(AppView<?> appView, Map<DexType, Map<FeatureSplit, List<DexType>>> services) {
    this.appView = appView;
    this.services = services;
  }

  public boolean isEmpty() {
    return services.isEmpty();
  }

  public Set<DexType> allServiceTypes() {
    assert verifyRewrittenWithLens();
    return services.keySet();
  }

  public List<DexType> serviceImplementationsFor(DexType serviceType) {
    assert verifyRewrittenWithLens();
    Map<FeatureSplit, List<DexType>> featureSplitListMap = services.get(serviceType);
    if (featureSplitListMap == null) {
      assert false
          : "Unexpected attempt to get service implementations for non-service type `"
              + serviceType.toSourceString()
              + "`";
      return ImmutableList.of();
    }
    ImmutableList.Builder<DexType> builder = ImmutableList.builder();
    for (List<DexType> implementations : featureSplitListMap.values()) {
      builder.addAll(implementations);
    }
    return builder.build();
  }

  public boolean hasServiceImplementationsInFeature(DexType serviceType) {
    if (appView.options().featureSplitConfiguration == null) {
      return false;
    }
    Map<FeatureSplit, List<DexType>> featureImplementations = services.get(serviceType);
    if (featureImplementations == null || featureImplementations.isEmpty()) {
      assert false
          : "Unexpected attempt to get service implementations for non-service type `"
              + serviceType.toSourceString()
              + "`";
      return true;
    }
    if (featureImplementations.size() > 1
        || !featureImplementations.containsKey(FeatureSplit.BASE)) {
      return true;
    }
    // Check if service is defined feature
    DexProgramClass serviceClass = appView.definitionForProgramType(serviceType);
    if (appView.options().featureSplitConfiguration.isInFeature(serviceClass)) {
      return true;
    }
    for (DexType dexType : featureImplementations.get(FeatureSplit.BASE)) {
      DexProgramClass implementationClass = appView.definitionForProgramType(dexType);
      if (appView.options().featureSplitConfiguration.isInFeature(implementationClass)) {
        return true;
      }
    }
    return false;
  }

  public AppServices rewrittenWithLens(GraphLens graphLens) {
    ImmutableMap.Builder<DexType, Map<FeatureSplit, List<DexType>>> rewrittenFeatureMappings =
        ImmutableMap.builder();
    for (Entry<DexType, Map<FeatureSplit, List<DexType>>> entry : services.entrySet()) {
      DexType rewrittenServiceType = graphLens.lookupType(entry.getKey());
      ImmutableMap.Builder<FeatureSplit, List<DexType>> rewrittenFeatureImplementations =
          ImmutableMap.builder();
      for (Entry<FeatureSplit, List<DexType>> featureSplitImpls : entry.getValue().entrySet()) {
        ImmutableList.Builder<DexType> rewrittenServiceImplementationTypes =
            ImmutableList.builder();
        for (DexType serviceImplementationType : featureSplitImpls.getValue()) {
          rewrittenServiceImplementationTypes.add(graphLens.lookupType(serviceImplementationType));
        }
        rewrittenFeatureImplementations.put(
            featureSplitImpls.getKey(), rewrittenServiceImplementationTypes.build());
      }
      rewrittenFeatureMappings.put(rewrittenServiceType, rewrittenFeatureImplementations.build());
    }
    return new AppServices(appView, rewrittenFeatureMappings.build());
  }

  public AppServices prunedCopy(Collection<DexType> removedClasses) {
    ImmutableMap.Builder<DexType, Map<FeatureSplit, List<DexType>>> rewrittenServicesBuilder =
        ImmutableMap.builder();
    for (Entry<DexType, Map<FeatureSplit, List<DexType>>> entry : services.entrySet()) {
      if (removedClasses.contains(entry.getKey())) {
        continue;
      }
      ImmutableMap.Builder<FeatureSplit, List<DexType>> prunedFeatureSplitImpls =
          ImmutableMap.builder();
      for (Entry<FeatureSplit, List<DexType>> featureSplitEntry : entry.getValue().entrySet()) {
        ImmutableList.Builder<DexType> rewrittenServiceImplementationTypesBuilder =
            ImmutableList.builder();
        for (DexType serviceImplementationType : featureSplitEntry.getValue()) {
          if (!removedClasses.contains(serviceImplementationType)) {
            rewrittenServiceImplementationTypesBuilder.add(serviceImplementationType);
          }
        }
        List<DexType> prunedFeatureSplitImplementations =
            rewrittenServiceImplementationTypesBuilder.build();
        if (prunedFeatureSplitImplementations.size() > 0) {
          prunedFeatureSplitImpls.put(
              featureSplitEntry.getKey(), rewrittenServiceImplementationTypesBuilder.build());
        }
      }
      ImmutableMap<FeatureSplit, List<DexType>> prunedServiceImplementations =
          prunedFeatureSplitImpls.build();
      if (prunedServiceImplementations.size() > 0) {
        rewrittenServicesBuilder.put(entry.getKey(), prunedServiceImplementations);
      }
    }
    return new AppServices(appView, rewrittenServicesBuilder.build());
  }

  private boolean verifyRewrittenWithLens() {
    for (Entry<DexType, Map<FeatureSplit, List<DexType>>> entry : services.entrySet()) {
      assert entry.getKey() == appView.graphLens().lookupType(entry.getKey());
      for (Entry<FeatureSplit, List<DexType>> featureEntry : entry.getValue().entrySet()) {
        for (DexType type : featureEntry.getValue()) {
          assert type == appView.graphLens().lookupType(type);
        }
      }
    }
    return true;
  }

  public void visit(BiConsumer<DexType, List<DexType>> consumer) {
    services.forEach(
        (type, featureImpls) -> {
          ImmutableList.Builder<DexType> builder = ImmutableList.builder();
          featureImpls.values().forEach(builder::addAll);
          consumer.accept(type, builder.build());
        });
  }

  public static Builder builder(AppView<?> appView) {
    return new Builder(appView);
  }

  public static class Builder {

    private final AppView<?> appView;
    private final Map<DexType, Map<FeatureSplit, List<DexType>>> services = new LinkedHashMap<>();

    private Builder(AppView<?> appView) {
      this.appView = appView;
    }

    public AppServices build() {
      for (DataResourceProvider provider : appView.appInfo().app().dataResourceProviders) {
        readServices(provider, FeatureSplit.BASE);
      }
      if (appView.options().featureSplitConfiguration != null) {
        List<FeatureSplit> featureSplits =
            appView.options().featureSplitConfiguration.getFeatureSplits();
        for (FeatureSplit featureSplit : featureSplits) {
          for (ProgramResourceProvider provider : featureSplit.getProgramResourceProviders()) {
            DataResourceProvider dataResourceProvider = provider.getDataResourceProvider();
            if (dataResourceProvider != null) {
              readServices(dataResourceProvider, featureSplit);
            }
          }
        }
      }
      return new AppServices(appView, services);
    }

    private void readServices(
        DataResourceProvider dataResourceProvider, FeatureSplit featureSplit) {
      try {
        dataResourceProvider.accept(new DataResourceProviderVisitor(featureSplit));
      } catch (ResourceException e) {
        throw new CompilationError(e.getMessage(), e);
      }
    }

    private class DataResourceProviderVisitor implements Visitor {

      private final FeatureSplit featureSplit;

      public DataResourceProviderVisitor(FeatureSplit featureSplit) {
        this.featureSplit = featureSplit;
      }

      @Override
      public void visit(DataDirectoryResource directory) {
        // Ignore.
      }

      @Override
      public void visit(DataEntryResource file) {
        try {
          String name = file.getName();
          if (name.startsWith(SERVICE_DIRECTORY_NAME)) {
            String serviceName = name.substring(SERVICE_DIRECTORY_NAME.length());
            if (DescriptorUtils.isValidJavaType(serviceName)) {
              String serviceDescriptor = DescriptorUtils.javaTypeToDescriptor(serviceName);
              DexType serviceType = appView.dexItemFactory().createType(serviceDescriptor);
              byte[] bytes = ByteStreams.toByteArray(file.getByteStream());
              String contents = new String(bytes, Charset.defaultCharset());
              Map<FeatureSplit, List<DexType>> featureSplitImplementations =
                  services.computeIfAbsent(serviceType, k -> new LinkedHashMap<>());
              List<DexType> serviceImplementations =
                  featureSplitImplementations.computeIfAbsent(featureSplit, f -> new ArrayList<>());
              readServiceImplementationsForService(
                  contents, file.getOrigin(), serviceImplementations);
            }
          }
        } catch (IOException | ResourceException e) {
          throw new CompilationError(e.getMessage(), e);
        }
      }

      private void readServiceImplementationsForService(
          String contents, Origin origin, List<DexType> serviceImplementations) {
        if (contents != null) {
          StringUtils.splitLines(contents).stream()
              .map(String::trim)
              .map(this::prefixUntilCommentChar)
              .filter(line -> !line.isEmpty())
              .filter(DescriptorUtils::isValidJavaType)
              .map(DescriptorUtils::javaTypeToDescriptor)
              .map(appView.dexItemFactory()::createType)
              .filter(
                  serviceImplementationType -> {
                    if (!serviceImplementationType.isClassType()) {
                      // Should never happen.
                      appView
                          .options()
                          .reporter
                          .warning(
                              new StringDiagnostic(
                                  "Unexpected service implementation found in META-INF/services/: `"
                                      + serviceImplementationType.toSourceString()
                                      + "`.",
                                  origin));
                      return false;
                    }
                    // Only keep one of each implementation type in the list.
                    return !serviceImplementations.contains(serviceImplementationType);
                  })
              .forEach(serviceImplementations::add);
        }
      }

      private String prefixUntilCommentChar(String line) {
        int commentCharIndex = line.indexOf('#');
        return commentCharIndex > -1 ? line.substring(0, commentCharIndex) : line;
      }
    }
  }
}
