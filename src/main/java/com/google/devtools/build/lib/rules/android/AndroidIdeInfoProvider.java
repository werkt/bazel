// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.OutputJar;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/** An Android target provider to provide Android-specific info to IDEs. */
@Immutable
public final class AndroidIdeInfoProvider implements TransitiveInfoProvider {
  /** Represents a directory that contains sources, generated or otherwise, for an IDE. */
  @Immutable
  public static class SourceDirectory {
    final PathFragment relativePath;
    final PathFragment rootPath;
    final boolean isSource;

    @VisibleForTesting
    public static SourceDirectory fromSourceRoot(PathFragment rootPath, PathFragment relativePath) {
      return new SourceDirectory(rootPath, relativePath, true);
    }

    public static SourceDirectory fromRoot(Root root, PathFragment relativePath) {
      return new SourceDirectory(
          root.getPath().asFragment(), relativePath, root.isSourceRoot());
    }

    private SourceDirectory(
        PathFragment rootPath,
        PathFragment relativePath,
        boolean isSource) {
      this.rootPath = rootPath;
      this.relativePath = relativePath;
      this.isSource = isSource;
    }

    /** The root relative path, {@link Artifact#getRootRelativePath()}. */
    public PathFragment getRelativePath() {
      return relativePath;
    }

    /** The absolute path of the root that contains this directory, {@link Root#getPath()}. */
    public PathFragment getRootPath() {
      return rootPath;
    }

    /** Indicates if the directory is in the gen files tree. */
    public boolean isSource() {
      return isSource;
    }

    @Override
    public int hashCode() {
      return Objects.hash(relativePath, rootPath, isSource);
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof SourceDirectory) {
        SourceDirectory otherDir = (SourceDirectory) other;
        return Objects.equals(rootPath, otherDir.rootPath)
            && Objects.equals(relativePath, otherDir.relativePath)
            && Objects.equals(isSource, otherDir.isSource);
      }
      return false;
    }

    @Override
    public String toString() {
      return "SourceDirectory [relativePath="
          + relativePath
          + ", rootPath="
          + rootPath
          + ", isSource="
          + isSource
          + "]";
    }
  }

  /** Builder for {@link AndroidIdeInfoProvider} */
  public static class Builder {
    private Artifact manifest = null;
    private Artifact generatedManifest = null;
    private Artifact apk = null;
    private Artifact resourceApk = null;
    private Artifact idlClassJar = null;
    private Artifact idlSourceJar = null;
    private OutputJar resourceJar = null;
    private String javaPackage = null;
    private String idlImportRoot = null;
    private final Set<SourceDirectory> resourceDirs = new LinkedHashSet<>();
    private final Set<SourceDirectory> assetDirs = new LinkedHashSet<>();
    private final Set<SourceDirectory> idlDirs = new LinkedHashSet<>();
    private final Set<Artifact> idlSrcs = new LinkedHashSet<>();
    private final Set<Artifact> idlGeneratedJavaFiles = new LinkedHashSet<>();
    private final Set<Artifact> apksUnderTest = new LinkedHashSet<>();
    private boolean definesAndroidResources;
    private Artifact aar = null;
    private Map<String, NestedSet<Artifact>> nativeLibs = null;

    public AndroidIdeInfoProvider build() {
      return new AndroidIdeInfoProvider(
          javaPackage,
          idlImportRoot,
          manifest,
          generatedManifest,
          apk,
          idlClassJar,
          idlSourceJar,
          resourceJar,
          definesAndroidResources,
          aar,
          ImmutableList.copyOf(assetDirs),
          ImmutableList.copyOf(resourceDirs),
          ImmutableList.copyOf(idlDirs),
          ImmutableList.copyOf(idlSrcs),
          ImmutableList.copyOf(idlGeneratedJavaFiles),
          ImmutableList.copyOf(apksUnderTest),
          nativeLibs != null
              ? ImmutableMap.copyOf(nativeLibs)
              : ImmutableMap.<String, NestedSet<Artifact>>of(),
          resourceApk);
    }

    public Builder setJavaPackage(String javaPackage) {
      this.javaPackage = javaPackage;
      return this;
    }

    public Builder setDefinesAndroidResources(boolean definesAndroidResources) {
      this.definesAndroidResources = definesAndroidResources;
      return this;
    }

    public Builder setApk(Artifact apk) {
      Preconditions.checkState(this.apk == null);
      this.apk = apk;
      return this;
    }

    public Builder setManifest(Artifact manifest) {
      Preconditions.checkState(this.manifest == null);
      this.manifest = manifest;
      return this;
    }

    public Builder setGeneratedManifest(Artifact manifest) {
      Preconditions.checkState(this.generatedManifest == null);
      this.generatedManifest = manifest;
      return this;
    }

    public Builder setIdlClassJar(@Nullable Artifact idlClassJar) {
      Preconditions.checkState(this.idlClassJar == null);
      this.idlClassJar = idlClassJar;
      return this;
    }

    public Builder setIdlSourceJar(@Nullable Artifact idlSourceJar) {
      Preconditions.checkState(this.idlSourceJar == null);
      this.idlSourceJar = idlSourceJar;
      return this;
    }

    public Builder setResourceJar(OutputJar resourceJar) {
      this.resourceJar = resourceJar;
      return this;
    }

    public Builder setResourceApk(Artifact resourceApk) {
      this.resourceApk = resourceApk;
      return this;
    }

    public Builder setAar(Artifact aar) {
      this.aar = aar;
      return this;
    }

    public Builder setNativeLibs(Map<String, NestedSet<Artifact>> nativeLibs) {
      this.nativeLibs = nativeLibs;
      return this;
    }

    public Builder addIdlImportRoot(String idlImportRoot) {
      this.idlImportRoot = idlImportRoot;
      return this;
    }

    /** Add "idl_srcs" contents. */
    public Builder addIdlSrcs(Collection<Artifact> idlSrcs) {
      this.idlSrcs.addAll(idlSrcs);
      addIdlDirs(idlSrcs);
      return this;
    }

    /** Add the java files generated from "idl_srcs". */
    public Builder addIdlGeneratedJavaFiles(Collection<Artifact> idlGeneratedJavaFiles) {
      this.idlGeneratedJavaFiles.addAll(idlGeneratedJavaFiles);
      return this;
    }

    /** Add "idl_parcelables" contents. */
    public Builder addIdlParcelables(Collection<Artifact> idlParcelables) {
      addIdlDirs(idlParcelables);
      return this;
    }

    private void addIdlDirs(Collection<Artifact> idlArtifacts) {
      for (Artifact idl : idlArtifacts) {
        this.idlDirs.add(
            SourceDirectory.fromRoot(
                idl.getRoot(), idl.getRootRelativePath().getParentDirectory()));
      }
    }

    public Builder addResourceSource(Artifact resource) {
      resourceDirs.add(
          SourceDirectory.fromRoot(
              resource.getRoot(),
              AndroidCommon.getSourceDirectoryRelativePathFromResource(resource)));
      return this;
    }

    public Builder addResourceSources(Collection<Artifact> resources) {
      for (Artifact resource : resources) {
        addResourceSource(resource);
      }
      return this;
    }

    public Builder addAssetSources(Collection<Artifact> assets, PathFragment assetDir) {
      for (Artifact asset : assets) {
        addAssetSource(asset, assetDir);
      }
      return this;
    }

    public Builder addAssetSource(Artifact asset, PathFragment assetDir) {
      assetDirs.add(
          SourceDirectory.fromRoot(
              asset.getRoot(), AndroidCommon.trimTo(asset.getRootRelativePath(), assetDir)));
      return this;
    }

    public Builder addAllApksUnderTest(Iterable<Artifact> apks) {
      Iterables.addAll(apksUnderTest, apks);
      return this;
    }
  }

  private final String javaPackage;
  private final String idlImportRoot;
  private final Artifact manifest;
  private final Artifact generatedManifest;
  private final Artifact signedApk;
  @Nullable private final Artifact idlClassJar;
  @Nullable private final Artifact idlSourceJar;
  @Nullable private final OutputJar resourceJar;
  @Nullable private final Artifact resourceApk;
  private final ImmutableCollection<SourceDirectory> resourceDirs;
  private final boolean definesAndroidResources;
  private final Artifact aar;
  private final ImmutableCollection<SourceDirectory> assetDirs;
  private final ImmutableCollection<SourceDirectory> idlImports;
  private final ImmutableCollection<Artifact> idlSrcs;
  private final ImmutableCollection<Artifact> idlGeneratedJavaFiles;
  private final ImmutableCollection<Artifact> apksUnderTest;
  private final ImmutableMap<String, NestedSet<Artifact>> nativeLibs;

  AndroidIdeInfoProvider(
      String javaPackage,
      String idlImportRoot,
      @Nullable Artifact manifest,
      @Nullable Artifact generatedManifest,
      @Nullable Artifact signedApk,
      @Nullable Artifact idlClassJar,
      @Nullable Artifact idlSourceJar,
      @Nullable OutputJar resourceJar,
      boolean definesAndroidResources,
      @Nullable Artifact aar,
      ImmutableCollection<SourceDirectory> assetDirs,
      ImmutableCollection<SourceDirectory> resourceDirs,
      ImmutableCollection<SourceDirectory> idlImports,
      ImmutableCollection<Artifact> idlSrcs,
      ImmutableCollection<Artifact> idlGeneratedJavaFiles,
      ImmutableCollection<Artifact> apksUnderTest,
      ImmutableMap<String, NestedSet<Artifact>> nativeLibs,
      Artifact resourceApk) {
    this.javaPackage = javaPackage;
    this.idlImportRoot = idlImportRoot;
    this.manifest = manifest;
    this.generatedManifest = generatedManifest;
    this.signedApk = signedApk;
    this.idlClassJar = idlClassJar;
    this.idlSourceJar = idlSourceJar;
    this.resourceJar = resourceJar;
    this.definesAndroidResources = definesAndroidResources;
    this.aar = aar;
    this.assetDirs = assetDirs;
    this.resourceDirs = resourceDirs;
    this.idlImports = idlImports;
    this.idlSrcs = idlSrcs;
    this.idlGeneratedJavaFiles = idlGeneratedJavaFiles;
    this.apksUnderTest = apksUnderTest;
    this.nativeLibs = nativeLibs;
    this.resourceApk = resourceApk;
  }

  /** Returns java package for this target. */
  public String getJavaPackage() {
    return javaPackage;
  }

  /** Returns the direct AndroidManifest. */
  @Nullable
  public Artifact getManifest() {
    return manifest;
  }

  /** Returns the direct generated AndroidManifest. */
  @Nullable
  public Artifact getGeneratedManifest() {
    return generatedManifest;
  }

  /**
   * Returns true if the target defined Android resources. Exposes {@link
   * LocalResourceContainer#definesAndroidResources(AttributeMap)}
   */
  public boolean definesAndroidResources() {
    return this.definesAndroidResources;
  }

  @Nullable
  public String getIdlImportRoot() {
    return idlImportRoot;
  }

  /** Returns the direct debug key signed apk, if there is one. */
  @Nullable
  public Artifact getSignedApk() {
    return signedApk;
  }

  @Nullable
  public Artifact getIdlClassJar() {
    return idlClassJar;
  }

  @Nullable
  public Artifact getIdlSourceJar() {
    return idlSourceJar;
  }

  @Nullable
  public OutputJar getResourceJar() {
    return resourceJar;
  }

  @Nullable
  public Artifact getAar() {
    return aar;
  }

  @Nullable
  public Artifact getResourceApk() {
    return resourceApk;
  }

  /** A list of the direct Resource directories. */
  public ImmutableCollection<SourceDirectory> getResourceDirs() {
    return resourceDirs;
  }

  /** A list of the direct Asset directories. */
  public ImmutableCollection<SourceDirectory> getAssetDirs() {
    return assetDirs;
  }

  /** A list of direct idl directories. */
  public ImmutableCollection<SourceDirectory> getIdlImports() {
    return idlImports;
  }

  /** A list of sources from the "idl_srcs" attribute. */
  public ImmutableCollection<Artifact> getIdlSrcs() {
    return idlSrcs;
  }

  /** A list of java files generated from the "idl_srcs" attribute. */
  public ImmutableCollection<Artifact> getIdlGeneratedJavaFiles() {
    return idlGeneratedJavaFiles;
  }

  /** A list of the APKs related to the app under test, if any. */
  public ImmutableCollection<Artifact> getApksUnderTest() {
    return apksUnderTest;
  }

  /** A map, keyed on architecture, of the native libs for the app, if any. */
  public ImmutableMap<String, NestedSet<Artifact>> getNativeLibs() {
    return nativeLibs;
  }
}
