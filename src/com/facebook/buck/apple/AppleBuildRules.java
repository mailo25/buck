/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.halide.HalideLibraryDescription;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

/**
 * Helpers for reading properties of Apple target build rules.
 */
public final class AppleBuildRules {

  private static final Logger LOG = Logger.get(AppleBuildRules.class);

  // Utility class not to be instantiated.
  private AppleBuildRules() { }

  public static final ImmutableSet<BuildRuleType> XCODE_TARGET_BUILD_RULE_TYPES =
      ImmutableSet.of(
          Description.getBuildRuleType(AppleLibraryDescription.class),
          Description.getBuildRuleType(CxxLibraryDescription.class),
          Description.getBuildRuleType(AppleBinaryDescription.class),
          Description.getBuildRuleType(AppleBundleDescription.class),
          Description.getBuildRuleType(AppleTestDescription.class),
          Description.getBuildRuleType(HalideLibraryDescription.class));

  private static final ImmutableSet<Class<? extends BuildRule>> XCODE_TARGET_BUILD_RULE_TEST_TYPES =
      ImmutableSet.of(AppleTest.class);

  private static final ImmutableSet<BuildRuleType> RECURSIVE_DEPENDENCIES_STOP_AT_TYPES =
      ImmutableSet.of(
          Description.getBuildRuleType(AppleBundleDescription.class),
          Description.getBuildRuleType(AppleResourceDescription.class));

  private static final ImmutableSet<AppleBundleExtension> XCODE_TARGET_TEST_BUNDLE_EXTENSIONS =
      ImmutableSet.of(AppleBundleExtension.XCTEST);

  /**
   * Whether the build rule type is equivalent to some kind of Xcode target.
   */
  public static boolean isXcodeTargetBuildRuleType(@Nullable BuildRuleType type) {
    return XCODE_TARGET_BUILD_RULE_TYPES.contains(type);
  }

  /**
   * Whether the build rule type is a test target.
   */
  public static boolean isXcodeTargetTestBuildRule(BuildRule rule) {
    return XCODE_TARGET_BUILD_RULE_TEST_TYPES.contains(rule.getClass());
  }

  /**
   * Whether the bundle extension is a test bundle extension.
   */
  public static boolean isXcodeTargetTestBundleExtension(AppleBundleExtension extension) {
    return XCODE_TARGET_TEST_BUNDLE_EXTENSIONS.contains(extension);
  }

  public static String getOutputFileNameFormatForLibrary(boolean isSharedLibrary) {
    if (isSharedLibrary) {
      return "lib%s.dylib";
    } else {
      return "lib%s.a";
    }
  }

  public enum RecursiveDependenciesMode {
    /**
     * Will traverse all rules that are built.
     */
    BUILDING,
    /**
     * Will also not traverse the dependencies of bundles, as those are copied inside the bundle.
     */
    COPYING,
    /**
     * Will also not traverse the dependencies of shared libraries, as those are linked already.
     */
    LINKING,
  }

  public static Iterable<TargetNode<?, ?>> getRecursiveTargetNodeDependenciesOfTypes(
      final TargetGraph targetGraph,
      final RecursiveDependenciesMode mode,
      final TargetNode<?, ?> targetNode,
      final Optional<ImmutableSet<BuildRuleType>> types) {
    LOG.verbose(
        "Getting recursive dependencies of node %s, mode %s, including only types %s\n",
        targetNode,
        mode,
        types);

    GraphTraversable<TargetNode<?, ?>> graphTraversable = node -> {
      if (!isXcodeTargetBuildRuleType(node.getType()) ||
          SwiftLibraryDescription.isSwiftTarget(node.getBuildTarget())) {
        return Collections.emptyIterator();
      }

      LOG.verbose("Finding children of node: %s", node);

      ImmutableSortedSet.Builder<TargetNode<?, ?>> defaultDepsBuilder =
          ImmutableSortedSet.naturalOrder();
      ImmutableSortedSet.Builder<TargetNode<?, ?>> exportedDepsBuilder =
          ImmutableSortedSet.naturalOrder();
      addDirectAndExportedDeps(targetGraph, node, defaultDepsBuilder, exportedDepsBuilder);
      ImmutableSortedSet<TargetNode<?, ?>> defaultDeps = defaultDepsBuilder.build();
      ImmutableSortedSet<TargetNode<?, ?>> exportedDeps = exportedDepsBuilder.build();

      if (node.getType().equals(Description.getBuildRuleType(AppleBundleDescription.class))) {
        AppleBundleDescription.Arg arg =
            (AppleBundleDescription.Arg) node.getConstructorArg();

        ImmutableSortedSet.Builder<TargetNode<?, ?>> editedDeps =
            ImmutableSortedSet.naturalOrder();
        ImmutableSortedSet.Builder<TargetNode<?, ?>> editedExportedDeps =
            ImmutableSortedSet.naturalOrder();
        for (TargetNode<?, ?> rule : defaultDeps) {
          if (!rule.getBuildTarget().equals(arg.binary)) {
            editedDeps.add(rule);
          } else {
            addDirectAndExportedDeps(
                targetGraph,
                targetGraph.get(arg.binary),
                editedDeps,
                editedExportedDeps);
          }
        }

        ImmutableSortedSet<TargetNode<?, ?>> newDefaultDeps = editedDeps.build();
        ImmutableSortedSet<TargetNode<?, ?>> newExportedDeps = editedExportedDeps.build();
        LOG.verbose(
            "Transformed deps for bundle %s: %s -> %s, exported deps %s -> %s",
            node,
            defaultDeps,
            newDefaultDeps,
            exportedDeps,
            newExportedDeps);
        defaultDeps = newDefaultDeps;
        exportedDeps = newExportedDeps;
      }

      LOG.verbose("Default deps for node %s mode %s: %s", node, mode, defaultDeps);
      if (!exportedDeps.isEmpty()) {
        LOG.verbose("Exported deps for node %s mode %s: %s", node, mode, exportedDeps);
      }

      ImmutableSortedSet<TargetNode<?, ?>> deps = ImmutableSortedSet.of();

      if (node != targetNode) {
        switch (mode) {
          case LINKING:
            boolean nodeIsAppleLibrary =
                node.getType().equals(Description.getBuildRuleType(AppleLibraryDescription.class));
            boolean nodeIsCxxLibrary =
                node.getType().equals(Description.getBuildRuleType(CxxLibraryDescription.class));
            if (nodeIsAppleLibrary || nodeIsCxxLibrary) {
              if (AppleLibraryDescription.isSharedLibraryTarget(node.getBuildTarget())) {
                deps = exportedDeps;
              } else {
                deps = defaultDeps;
              }
            } else if (RECURSIVE_DEPENDENCIES_STOP_AT_TYPES.contains(node.getType())) {
              deps = exportedDeps;
            } else {
              deps = defaultDeps;
            }
            break;
          case COPYING:
            if (RECURSIVE_DEPENDENCIES_STOP_AT_TYPES.contains(node.getType())) {
              deps = exportedDeps;
            } else {
              deps = defaultDeps;
            }
            break;
          case BUILDING:
            deps = defaultDeps;
            break;
        }
      } else {
        deps = defaultDeps;
      }

      LOG.verbose("Walking children of node %s: %s", node, deps);
      return deps.iterator();
    };

    final ImmutableList.Builder<TargetNode<?, ?>> filteredRules = ImmutableList.builder();
    AcyclicDepthFirstPostOrderTraversal<TargetNode<?, ?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(graphTraversable);
    try {
      for (TargetNode<?, ?> node : traversal.traverse(ImmutableList.of(targetNode))) {
        if (node != targetNode &&
            (!types.isPresent() || types.get().contains(node.getType()))) {
          filteredRules.add(node);
        }
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      // actual load failures and cycle exceptions should have been caught at an earlier stage
      throw new RuntimeException(e);
    }
    ImmutableList<TargetNode<?, ?>> result = filteredRules.build();
    LOG.verbose(
        "Got recursive dependencies of node %s mode %s types %s: %s\n",
        targetNode,
        mode,
        types,
        result);

    return result;
  }

  private static void addDirectAndExportedDeps(
      TargetGraph targetGraph,
      TargetNode<?, ?> targetNode,
      ImmutableSortedSet.Builder<TargetNode<?, ?>> directDepsBuilder,
      ImmutableSortedSet.Builder<TargetNode<?, ?>> exportedDepsBuilder
  ) {
    directDepsBuilder.addAll(targetGraph.getAll(targetNode.getDeps()));
    if (targetNode.getType() == Description.getBuildRuleType(AppleLibraryDescription.class) ||
        targetNode.getType() == Description.getBuildRuleType(CxxLibraryDescription.class)) {
      CxxLibraryDescription.Arg arg =
          (CxxLibraryDescription.Arg) targetNode.getConstructorArg();
      LOG.verbose("Exported deps of node %s: %s", targetNode, arg.exportedDeps);
      Iterable<TargetNode<?, ?>> exportedNodes = targetGraph.getAll(arg.exportedDeps);
      directDepsBuilder.addAll(exportedNodes);
      exportedDepsBuilder.addAll(exportedNodes);
    }
  }

  public static ImmutableSet<TargetNode<?, ?>> getSchemeBuildableTargetNodes(
      TargetGraph targetGraph,
      TargetNode<?, ?> targetNode) {
    Iterable<TargetNode<?, ?>> targetNodes = Iterables.concat(
        getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.empty()),
        ImmutableSet.of(targetNode));

    return ImmutableSet.copyOf(
        Iterables.filter(
            targetNodes,
            input -> isXcodeTargetBuildRuleType(input.getType())));
  }

  public static Function<TargetNode<?, ?>, Iterable<TargetNode<?, ?>>>
    newRecursiveRuleDependencyTransformer(
      final TargetGraph targetGraph,
      final RecursiveDependenciesMode mode,
      final ImmutableSet<BuildRuleType> types) {
    return input -> getRecursiveTargetNodeDependenciesOfTypes(
        targetGraph,
        mode,
        input,
        Optional.of(types));
  }

  public static <T> ImmutableSet<AppleAssetCatalogDescription.Arg>
  collectRecursiveAssetCatalogs(TargetGraph targetGraph, Iterable<TargetNode<T, ?>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            newRecursiveRuleDependencyTransformer(
                targetGraph,
                RecursiveDependenciesMode.COPYING,
                ImmutableSet.of(Description.getBuildRuleType(AppleAssetCatalogDescription.class))))
        .transform(
            input -> (AppleAssetCatalogDescription.Arg) input.getConstructorArg())
        .toSet();
  }

  public static <T> ImmutableSet<CoreDataModelDescription.Arg> collectTransitiveCoreDataModels(
      TargetGraph targetGraph,
      Collection<TargetNode<T, ?>> targetNodes) {
    return targetNodes
        .stream()
        .flatMap(
            targetNode -> StreamSupport.stream(
                newRecursiveRuleDependencyTransformer(
                    targetGraph,
                    RecursiveDependenciesMode.COPYING,
                    ImmutableSet.of(Description.getBuildRuleType(CoreDataModelDescription.class)))
                .apply(targetNode)
                .spliterator(), false))
        .map(input -> (CoreDataModelDescription.Arg) input.getConstructorArg())
        .collect(MoreCollectors.toImmutableSet());
  }

  public static ImmutableSet<AppleAssetCatalogDescription.Arg> collectDirectAssetCatalogs(
      TargetGraph targetGraph,
      TargetNode<?, ?> targetNode) {
    ImmutableSet.Builder<AppleAssetCatalogDescription.Arg> builder = ImmutableSet.builder();
    Iterable<TargetNode<?, ?>> deps = targetGraph.getAll(targetNode.getDeps());
    for (TargetNode<?, ?> node : deps) {
      if (node.getType().equals(Description.getBuildRuleType(AppleAssetCatalogDescription.class))) {
        builder.add((AppleAssetCatalogDescription.Arg) node.getConstructorArg());
      }
    }
    return builder.build();
  }

}