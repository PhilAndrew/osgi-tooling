package wav.devtools.sbt.karaf.packaging

import org.osgi.framework.Version
import sbt.Keys._
import sbt._
import wav.devtools.sbt.karaf.packaging.model._, FeaturesXml._

import scala.annotation.tailrec

private[packaging] object Resolution {
  
  import model.FeaturesArtifactData.canBeFeaturesRepository

  val featuresArtifactFilter = artifactFilter(name = "*", `type` = "xml", extension = "xml", classifier = "features")

  val bundleArtifactFilter = artifactFilter(name = "*", `type` = "jar" | "bundle", extension = "*", classifier = "*")

  def resolveFeaturesRepository(logger: Logger, mr: ModuleReport): Either[String, Seq[FeatureRepository]] = {
    val fas = for {
      (a, f) <- mr.artifacts
      if (canBeFeaturesRepository(a))
    } yield FeaturesArtifact(mr.module, a, Some(f), None)
    val notDownloaded = fas.filterNot(_.downloaded)
    if (notDownloaded.nonEmpty) Left(s"Failed to resolve all the features repositories for the module: ${mr.module}, missing artifact: ${notDownloaded.map(_.artifact.name)}")
    else Right(fas.flatMap { fa =>
      val r = fa.toRepository
      if (r.isEmpty) logger.warn(s"Ignored possible features repository, content not known: Artifact ${fa.artifact}, Module ${mr.module}")
      r
    })
  }

  def resolveAllFeatureRepositoriesTask: SbtTask[UpdateReport => Set[FeatureRepository]] = Def.task {
    val logger = streams.value.log
    ur => {
      val fas = ur.filter(featuresArtifactFilter)
      val results = fas.configurations.flatMap(_.modules).map(resolveFeaturesRepository(logger, _))
      val failures = results.collect { case Left(e) => e }
      failures.foreach(logger.error(_))
      if (failures.nonEmpty)
        sys.error("Could not resolve all features repositories.")
      results.collect { case Right(frs) => frs }.flatten.toSet
    }
  }

  def resolveRequiredFeatures(required: Set[Dependency], repositories: Set[FeatureRepository]): Either[Set[Dependency], Set[Feature]] = {
    val allFeatures = for {
      fr <- repositories
      f <- fr.featuresXml.elems.collect { case f: Feature => f }
    } yield f
    resolveFeatures(required, allFeatures)
  }

  def toMavenUrl(m: ModuleID, a: Artifact): MavenUrl =
    MavenUrl(m.organization, m.name, m.revision, Some(a.`type`), a.classifier)

  private val bundleTypes = Set("bundle", "jar")
  def toBundle(m: ModuleID, a: Artifact): Bundle = {
    val t = Some(a.`type`).filterNot(bundleTypes.contains)
    Bundle(MavenUrl(m.organization, m.name, m.revision, t, a.classifier).toString)
  }
  
  def toBundleID(url: MavenUrl): ModuleID =
      ModuleID(url.groupId, url.artifactId, url.version,
        explicitArtifacts = Seq(Artifact(url.artifactId,url.`type` getOrElse "jar", "jar", url.classifer, Nil, None, Map.empty)))

  def toLibraryDependencies(features: Set[Feature]): Seq[ModuleID] =
    features.flatMap(_.deps).collect {
      case b @ Bundle(MavenUrl(url), _, _, _) => toBundleID(url) % "provided"
    }.toSeq

  def selectProjectBundles(ur: UpdateReport, features: Set[Feature]): Set[Bundle] = {
    val mavenUrls = features
      .flatMap(_.deps)
      .collect { case Bundle(MavenUrl(url), _, _, _) => url }
    val cr = ur.filter(bundleArtifactFilter).configuration("runtime").get
    val inFeatures =
      for {
        mr <- cr.modules
        m = mr.module
        (a, _) <- mr.artifacts
        url <- mavenUrls
        if (url.groupId == m.organization && url.artifactId == m.name)
      } yield (m, a)
    (for {
        mr <- cr.modules
        m = mr.module
        (a, _) <- mr.artifacts
        if (!inFeatures.contains((m,a)))
      } yield toBundle(m,a)).toSet
  }

  def satisfies(constraint: Dependency, feature: Feature): Boolean =
    constraint.name == feature.name && (
      constraint.version.isEmpty || {
        var vr = constraint.version.get
        !vr.isEmpty() && (feature.version == Version.emptyVersion || vr.includes(feature.version))
      })

  def selectFeatureDeps(dep: Dependency, fs: Set[Feature]): Set[Dependency] =
    fs.filter(satisfies(dep, _)).flatMap(_.deps).collect { case dep: Dependency => dep }

  def selectFeatures(requested: Set[Dependency], fs: Set[Feature]): Either[Set[Dependency], Set[Feature]] = {
    val unsatisfied = for {
      constraint <- requested
      if (fs.forall(f => !satisfies(constraint, f)))
    } yield constraint
    if (unsatisfied.nonEmpty) Left(unsatisfied)
    else Right(
      for {
        constraint <- requested
        feature <- fs
        if (satisfies(constraint, feature))
      } yield feature
    )
  }

  @tailrec
  def resolveFeatures(requested: Set[Dependency], fs: Set[Feature], resolved: Set[Feature] = Set.empty): Either[Set[Dependency], Set[Feature]] = {
    if (requested.isEmpty) return Right(resolved)
    val result = selectFeatures(requested, fs)
    if (result.isLeft) result
    else {
      val Right(selection) = result
      val selectedRefs = selection.map(_.toDep)
      val resolvedRefs = resolved.map(_.toDep)
      val resolved2 = selection ++ resolved
      val unresolved = selectedRefs.flatMap(selectFeatureDeps(_, fs)) -- resolvedRefs
      resolveFeatures(unresolved, fs, resolved2)
    }
  }

  def downloadFeaturesRepository(
    logger: Logger,
    downloadArtifact: MavenUrl => Option[File],
    m: ModuleID): Either[String, Seq[FeatureRepository]] = {
    val as = m.explicitArtifacts.filter(model.FeaturesArtifactData.canBeFeaturesRepository)
    val fas = for {
      a <- as
      url = toMavenUrl(m,a)
      f = downloadArtifact(url)
    } yield FeaturesArtifact(m, a, f)
    val notDownloaded = fas.filterNot(_.downloaded)
    if (notDownloaded.nonEmpty) return Left(s"Failed to resolve all the features repositories for the module: $m, missing artifact: ${notDownloaded.map(_.artifact.name)}")
    Right(fas.flatMap { fa =>
      val r = fa.toRepository
      if (r.isEmpty) logger.warn(s"Ignored possible features repository, content not known: Artifact ${fa.artifact}, Module $m")
      r
    })
  }

  def mustResolveFeatures(selected: Either[Set[Dependency], Set[Feature]]): Set[Feature] = {
    selected match {
      case Left(unresolved) => sys.error(s"The following features could not be resolved: $unresolved")
      case Right(resolved) =>
        val duplicates = resolved.toSeq
          .map(_.name)
          .groupBy(identity)
          .mapValues(_.size)
          .filter(_._2 > 1)
          .keys
        if (duplicates.nonEmpty)
          sys.error(s"Could not select a unique feature for the following: $duplicates")
    }
    val Right(resolved) = selected
    resolved
  }

}