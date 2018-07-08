package com.wix.bazel.migrator.transform

import com.wix.bazel.migrator.model.SourceModule
import com.wix.build.maven.translation.MavenToBazelTranslations.`Maven Coordinates to Bazel rules`
import com.wixpress.build.bazel.{ImportExternalRule, LibraryRule, OverrideCoordinates}
import com.wixpress.build.maven.Coordinates
import com.wixpress.build.bazel.{ImportExternalRule, LibraryRule, WorkspaceRule}
import com.wixpress.build.maven.{ArchivePackaging, Coordinates, Packaging}
import com.wixpress.build.maven
import ModuleDependenciesTransformer.ProductionDepsTargetName
import com.wix.bazel.migrator.transform.MavenDependencyTransformer.DependencyExtensions

class MavenDependencyTransformer(repoModules: Set[SourceModule],
                                 externalPackageLocator: ExternalSourceModuleRegistry,
                                 archiveOverrideCoordinates: MavenArchiveTargetsOverrides) {

  def toBazelDependency(dependency: maven.Dependency): Option[String] = {
    if (ignoredDependency(dependency.coordinates)) None else Some(
      findInRepoModules(dependency.coordinates)
        .map(asRepoSourceDependency)
        .orElse(asExternalTargetDependency(dependency.coordinates))
        .getOrElse(asThirdPartyDependency(dependency))
    )
  }

  private def asExternalTargetDependency(coordinates: Coordinates) =
    externalPackageLocator.lookupBy(coordinates.groupId, coordinates.artifactId).map(_ + s":$ProductionDepsTargetName")

  private def ignoredDependency(coordinates: Coordinates) = coordinates.isProtoArtifact

  private def findInRepoModules(coordinates: Coordinates) = {
    repoModules
      .find(_.coordinates.equalsOnGroupIdAndArtifactId(coordinates))
  }

  private def asRepoSourceDependency(sourceModule: SourceModule): String = {
    val packageName = sourceModule.relativePathFromMonoRepoRoot
    s"//$packageName:$ProductionDepsTargetName"
  }


  private def asThirdPartyDependency(dependency: maven.Dependency): String = {
    dependency.coordinates.packaging match {
      case Packaging("jar") => asThirdPartyJarDependency(dependency)
      case Packaging("pom") => asThirdPartyPomDependency(dependency)
      case ArchivePackaging() => asExternalRepoArchive(dependency)
      case _ => throw new RuntimeException("unsupported dependency packaging on " + dependency.coordinates.serialized)
    }
  }

  private def asThirdPartyJarDependency(dependency: maven.Dependency): String = {
    ImportExternalRule.jarLabelBy(dependency.coordinates)
  }

  private def asThirdPartyPomDependency(dependency: maven.Dependency): String = {
    LibraryRule.nonJarLabelBy(dependency.coordinates)
  }

  private def asExternalRepoArchive(dependency: maven.Dependency): String = {
    val overrideCoordinates = archiveOverrideCoordinates.unpackedOverridesToArchive
    val filegroupTarget = overrideCoordinates.find(dependency.isEqual).map(_ => "archive").getOrElse("unpacked")
    WorkspaceRule.mavenArchiveLabelBy(dependency, filegroupTarget)
  }
}

object MavenDependencyTransformer {
  implicit class DependencyExtensions(dependency: maven.Dependency) {
    def isEqual(coordinates: OverrideCoordinates): Boolean = {
      coordinates.artifactId == dependency.coordinates.artifactId && coordinates.groupId == dependency.coordinates.groupId
    }
  }
}
