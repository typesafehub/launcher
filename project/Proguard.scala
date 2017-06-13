import sbt._
import Keys._
import Scope.{ GlobalScope, ThisScope }

object LaunchProguard {
  lazy val Proguard = config("proguard") hide;

  lazy val configurationFile = settingKey[File]("Location of the generated proguard configuration file.")
  lazy val proguard = taskKey[File]("Produces the final compacted jar that contains only the classes needed using proguard.")
  lazy val proguardConfiguration = taskKey[File]("Creates the configuration file to use with proguard.")
  lazy val options = taskKey[Seq[String]]("Proguard options.")
  lazy val optimizePasses = settingKey[Int]("Number of optimization passes proguard performs.")
  lazy val keepFullClasses = settingKey[Seq[String]]("Fully qualified names of classes that proguard should preserve the non-private API of.")

  lazy val settings: Seq[Setting[_]] =
    inScope(GlobalScope)(inConfig(Proguard)(globalSettings)) ++
      inConfig(Proguard)(baseSettings) :+
      (libraryDependencies += "net.sf.proguard" % "proguard-base" % "4.11" % Proguard.name)

  /** Defaults */
  def globalSettings = Seq(
    optimizePasses := 0, // disable optimization for dbuild
    keepFullClasses := Nil,
    options := basicOptions
  )
  def baseSettings = Seq(
    optimizeSetting,
    options ++= keepFullClasses.value map ("-keep public class " + _ + " {\n\tpublic protected * ;\n}"),
    options += "-dontobfuscate", // disable obfuscation for dbuild
    options += "-dontshrink",    // disable shrinking for dbuild
    configurationFile := target.value / "proguard.pro",
    proguardConfiguration := writeProguardConfiguration.value,
    proguard := proguardTask.value
  )

  /** Options to set the number of optimization passes or to disable optimization altogether. */
  def optimizeSetting = options ++= {
    val passes = optimizePasses.value
    if (passes <= 0)
      Seq("-dontoptimize")
    else
      Seq(
        "-optimizationpasses " + passes.toString,
        // optimization is problematic without this option, possibly proguard can't handle certain scalac-generated bytecode
        "-optimizations !code/allocation/variable"
      )
  }

  def specific(launchSub: Reference): Seq[Setting[_]] = inConfig(Proguard)(Seq(
    keepFullClasses ++= "xsbti.**" :: Nil,
    artifactPath := target.value / ("dbuild-launch-" + version.value + ".jar"),
    options ++= dependencyOptions(launchSub).value,
    options += "-injars " + mkpath(packageBin.value),
    packageBin := (packageBin in (launchSub, Compile)).value,
    options ++= mainClass.in(launchSub, Compile).value.toList map keepMain,
    options += "-outjars " + mkpath(artifactPath.value),
    fullClasspath := Classpaths.managedJars(configuration.value, classpathTypes.value, update.value)
  ))

  def basicOptions =
    Seq(
      "-keep,allowoptimization,allowshrinking class * { *; }", // no obfuscation
      "-keepattributes SourceFile,LineNumberTable", // preserve debugging information
      "-dontnote",
      "-dontwarn", // too many warnings generated for scalac-generated bytecode last time this was enabled
      "-verbose",
      "-ignorewarnings")

  /** Option to preserve the main entry point. */
  private def keepMain(className: String) =
    s"""-keep public class $className {
			|    public static void main(java.lang.String[]);
			|}""".stripMargin

  private def excludeIvyResources =
    "META-INF/**" ::
      "fr/**" ::
      "**/antlib.xml" ::
      "**/*.png" ::
      "org/apache/ivy/core/settings/ivyconf*.xml" ::
      "org/apache/ivy/core/settings/ivysettings-*.xml" ::
      "org/apache/ivy/plugins/resolver/packager/*" ::
      "**/ivy_vfs.xml" ::
      "org/apache/ivy/plugins/report/ivy-report-*" ::
      Nil

  // libraryFilter and the Scala library-specific filtering in mapInJars can be removed for 2.11, since it is properly modularized
  private def libraryFilter = "(!META-INF/**,!*.properties,!scala/util/parsing/*.class,**.class)"
  private def generalFilter = "(!META-INF/**,!*.properties)"

  def dependencyOptions(launchSub: Reference) = Def.task {
    val cp = (dependencyClasspath in (launchSub, Compile)).value
    val analysis = (compile in (launchSub, Compile)).value
    mapJars(cp.files, analysis.relations.allBinaryDeps.toSeq, streams.value.log)
  }

  def mapJars(in: Seq[File], all: Seq[File], log: Logger): Seq[String] =
    mapInJars(in, log) ++ mapLibraryJars(all filterNot in.toSet)
  def writeProguardConfiguration = Def.task {
    val content = options.value.mkString("\n")
    val conf = configurationFile.value
    if (!conf.exists || IO.read(conf) != content) {
      streams.value.log.info("Proguard configuration written to " + conf)
      IO.write(conf, content)
    }
    conf
  }

  def mapLibraryJars(libraryJars: Seq[File]): Seq[String] = libraryJars.map(f => "-libraryjars " + mkpath(f))
  def mapOutJar(outJar: File) = "-outjars " + mkpath(outJar)

  def mkpath(f: File): String = mkpath(f.getAbsolutePath, '\"')
  def mkpath(path: String, delimiter: Char): String = delimiter + path + delimiter

  def proguardTask = Def.task {
    val inJar = packageBin.value
    val outputJar = artifactPath.value
    val configFile = proguardConfiguration.value
    val f = FileFunction.cached(streams.value.cacheDirectory / "proguard", FilesInfo.hash) { _ =>
      runProguard(outputJar, configFile, fullClasspath.value.files, streams.value.log)
      Set(outputJar)
    }
    f(Set(inJar, configFile)) // make the assumption that if the classpath changed, the outputJar would change
    outputJar
  }
  def runProguard(outputJar: File, configFile: File, cp: Seq[File], log: Logger) {
    IO.delete(outputJar)
    val fileString = mkpath(configFile.getAbsolutePath, '\'')
    val exitValue = Process("java", List("-Xmx256M", "-cp", Path.makeString(cp), "proguard.ProGuard", "-include " + fileString)) ! log
    if (exitValue != 0) sys.error("Proguard failed with nonzero exit code (" + exitValue + ")")
  }

  def mapInJars(inJars: Seq[File], log: Logger): Seq[String] =
    {
      val (ivyJars, notIvy) = inJars partition isJarX("ivy")
      val (libraryJar, remaining) = notIvy partition isJarX("scala-library")
      val (compilerJar, otherJars) = remaining partition isJarX("scala-compiler")

      log.debug("proguard configuration:")
      log.debug("\tIvy jar location: " + ivyJars.mkString(", "))
      log.debug("\tOther jars:\n\t" + otherJars.mkString("\n\t"))

      ((withJar(ivyJars.toSeq, "Ivy") + excludeString(excludeIvyResources)) ::
        (withJar(libraryJar, "Scala library") + libraryFilter) ::
        otherJars.map(jar => mkpath(jar) + generalFilter).toList) map { "-injars " + _ }
    }

  private def excludeString(s: List[String]) = s.map("!" + _).mkString("(", ",", ")")

  private def withJar[T](files: Seq[File], name: String) = mkpath(files.headOption getOrElse sys.error(name + " not present"))
  private def isJarX(x: String)(file: File) =
    {
      val name = file.getName
      name.startsWith(x) && name.endsWith(".jar")
    }
}
