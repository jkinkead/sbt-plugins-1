package org.allenai.plugins.docker

import org.allenai.plugins.Utilities

import sbt.{
  settingKey,
  taskKey,
  Artifact,
  AttributeKey,
  AutoPlugin,
  Def,
  Hash,
  IO,
  Keys,
  ModuleID,
  Plugins,
  Runtime,
  SettingKey,
  Task,
  TaskKey
}
import sbt.plugins.JvmPlugin

import java.io.File

import scala.sys.process.Process

/** Plugin for building docker images. */
object DockerPlugin extends AutoPlugin {
  val AI2_PRIVATE_REGISTRY = "allenai-docker-private-docker.bintray.io"

  val DEFAULT_BASE_IMAGE = AI2_PRIVATE_REGISTRY + "/java:8"

  /** Requires the JvmPlugin, since this will be building a jar dependency tree. */
  override def requires: Plugins = JvmPlugin

  object autoImport {
    ////////////////////////////////////////////////////////////////////////////////////////////////
    // The following settings affect both the images and Dockerfiles generated by this plugin. When
    // you update these settings in a build.sbt file, you'll want to re-generate your Dockerfiles.
    ////////////////////////////////////////////////////////////////////////////////////////////////

    // The following three settings control how the generated image is tagged. The image portion of
    // image tags will be, for the main image:
    //   ${imageRegistryHost}/${imageNamePrefix}/${imageName}
    // and for the dependency image will be:
    //   ${imageRegistryHost}/${imageNamePrefix}/${imageName}-dependency
    //
    // See the documentation for details on which tags will be used by `dockerBuild` and
    // `dockerPush`.
    val imageRegistryHost: SettingKey[String] = settingKey[String](
      "The base name of the image you're creating. Defaults to " +
        "allenai-docker-private-docker.bintray.io ."
    )
    val imageNamePrefix: SettingKey[String] = settingKey[String](
      "The image name prefix (\"repository\", in Docker terms) of the image you're creating. " +
        "Defaults to organization.value.stripPrefix(\"org.allenai.\") . " +
        "This is typically the github repository name."
    )
    val imageName: SettingKey[String] = settingKey[String](
      "The name of the image you're creating. Defaults to the sbt project name (the `name` " +
        "setting key)."
    )

    val imageBase: SettingKey[String] = settingKey[String](
      "The base image to use when creating your image. Defaults to " + DEFAULT_BASE_IMAGE + "."
    )

    val dockerCopyMappings: SettingKey[Seq[(File, String)]] = settingKey[Seq[(File, String)]](
      "Mappings to add to the Docker image. See " +
        "http://www.scala-sbt.org/0.12.3/docs/Detailed-Topics/Mapping-Files.html for detailed info " +
        "on sbt mappings."
    )

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // The following keys are for staging, building, running, and pushing images. These should not
    // be overridden from the defaults unless you know what you're doing.
    ////////////////////////////////////////////////////////////////////////////////////////////////

    val dockerDependencyStage: TaskKey[File] = taskKey[File](
      "Builds a staged directory under target/docker/dependencies containing project dependencies."
    )

    val dockerMainStage: TaskKey[File] = taskKey[File](
      "Builds a staged directory under target/docker/main containing the staged project, minus " +
        "dependencies."
    )

    val dockerBuild: TaskKey[String] = taskKey[String](
      "Builds a docker image for this project, returning the image ID"
    )
  }

  /** Task initializer to look up non-local dependency artifacts for the current project. This
    * contains all direct and transitive dependencies pulled from remote sources.
    *
    * This task result contains the pairing of dependency jars with their target filenames.
    */
  lazy val remoteDependenciesDef: Def.Initialize[Task[Seq[(File, String)]]] = Def.task {
    // The runtime classpath includes all jars needed to run, as well as the target directories for
    // local dependencies (and the current project).
    val allDependencies = Keys.fullClasspath.in(Runtime).value
    // Filter out dependencies that don't have an artifact to include and those that aren't files
    // (the directory targets).
    val jarDependencies = allDependencies.filter { dependency =>
      dependency.get(Keys.artifact.key).nonEmpty && dependency.data.isFile
    }

    // Map to the file / rename pairings.
    jarDependencies.map { dependency =>
      val file = dependency.data
      val jarName = {
        val moduleIdOption = dependency.metadata.get(AttributeKey[ModuleID]("module-id"))
        val artifactOption = dependency.metadata.get(AttributeKey[Artifact]("artifact"))
        // Try to get the name from the artifact data; else, use the filename.
        val generatedNameOption = moduleIdOption.zip(artifactOption).headOption.map {
          case (moduleId, artifact) => Utilities.jarName(moduleId, artifact)
        }
        generatedNameOption.getOrElse(file.getName)
      }
      (file, jarName)
    }
  }

  /** Task to build a docker image containing the dependencies of the current project. This is used
    * as a base image for the main project image.
    *
    * The result of this task is the tag of the newly-created Docker image.
    */
  lazy val dependencyImage: Def.Initialize[Task[String]] = Def.task {
    val logger = Keys.streams.value.log

    // Create the destination directory.
    val destination = Keys.target.value.toPath.resolve("docker").resolve("dependencies")
    IO.createDirectory(destination.toFile)
    val lib = destination.resolve("lib")

    // Copy all of the library dependencies, saving the end location.
    val copiedFiles: Seq[File] = remoteDependenciesDef.value.map {
      case (file, destination) =>
        val destinationFile = lib.resolve(destination).toFile
        IO.copyFile(file, destinationFile)
        destinationFile
    }

    // Calculate the checksum of these files. First, we calculate the individual file checksums,
    // then we sort the checksum list, and finally we take the checksum of the resulting list.
    val sortedFileHashes = copiedFiles.map(Hash.apply).map(Hash.toHex).sorted
    val dependencyHash = Hash.toHex(Hash(sortedFileHashes.mkString))

    val hashFile = destination.resolve("dependencies.sha1").toFile

    // Now we check to see if the dependency contents have changed since the last time we sent them
    // to docker.
    val oldDependencyHash = if (hashFile.exists) {
      IO.read(hashFile)
    } else {
      ""
    }

    if (dependencyHash != oldDependencyHash) {
      // Remove any items in `lib` that are stale.
      val staleItems = lib.toFile.listFiles.toSet -- copiedFiles
      staleItems.foreach(_.delete())

      // Create the simple dockerfile for the image containing the library dependencies.
      val dockerfile = destination.resolve("Dockerfile").toFile
      val dockerfileContents = """
      |FROM allenai-docker-private-docker.bintray.io/java:8
      |
      |WORKDIR /local/deploy
      |
      |COPY lib lib
      |""".stripMargin
      IO.write(dockerfile, dockerfileContents)

      // Build a new docker image.
      val dependenciesName = autoImport.imageName.value + "-dependencies"
      val dockerCommand = Seq(
        "docker",
        "build",
        "-t", dependenciesName,
        "-t", dependenciesName + ":" + dependencyHash,
        destination.toString
      )
      logger.info("Building dependency image . . .")
      val exitCode = Process(dockerCommand).!
      if (exitCode != 0) {
        sys.error("Error running " + dockerCommand.mkString(" "))
      }

      // Write out the hash file.
      IO.write(hashFile, dependencyHash)
    } else {
      logger.info("Dependency image unchanged.")
    }

    dependencyHash
  }

  lazy val dockerBuildTask = Def.task {
    dependencyImage.value
  }

  /** Adds the settings to configure the `dockerBuild` command. */
  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    autoImport.imageRegistryHost := AI2_PRIVATE_REGISTRY,
    autoImport.imageNamePrefix := Keys.organization.value.stripPrefix("org.allenai."),
    autoImport.imageName := Keys.name.value,
    autoImport.imageBase := DEFAULT_BASE_IMAGE,
    autoImport.dockerBuild := dockerBuildTask.value
  )
}
