package org.lflang.generator.docker;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.text.StringEscapeUtils;
import org.lflang.LocalStrings;
import org.lflang.generator.LFGeneratorContext;
import org.lflang.generator.SubContext;
import org.lflang.target.property.BuildCommandsProperty;
import org.lflang.target.property.DockerProperty;
import org.lflang.target.property.DockerProperty.DockerOptions;
import org.lflang.util.StringUtil;

/**
 * A class for generating docker files.
 *
 * @author Marten Lohstroh
 * @author Hou Seng Wong
 */
public abstract class DockerGenerator {

  /** Configuration for interactions with the filesystem. */
  protected final LFGeneratorContext context;

  /**
   * The constructor for the base docker file generation class.
   *
   * @param context The context of the code generator.
   */
  public DockerGenerator(LFGeneratorContext context) {
    this.context = context;
  }

  /** Generate the contents of the docker file. */
  protected String generateDockerFileContent() {
    var lfModuleName = context.getFileConfig().name;
    return String.join(
        "\n",
        generateHeader(),
        "FROM " + builderBase() + " AS builder",
        "WORKDIR /lingua-franca/" + lfModuleName,
        generateRunForInstallingDeps(),
        generateCopyForSources(),
        generateRunForBuild(),
        "",
        "FROM " + runnerBase(),
        "WORKDIR /lingua-franca",
        "RUN mkdir scripts",
        generateCopyOfScript(),
        generateRunForMakingExecutableDir(),
        generateCopyOfExecutable(),
        generateEntryPoint(),
        "");
  }

  /** Return a RUN command for making a directory to place executables in. */
  protected String generateRunForMakingExecutableDir() {
    return "RUN mkdir bin";
  }

  /** Return a COPY command for copying sources from host into container. */
  protected String generateCopyForSources() {
    return "COPY . src-gen";
  }

  /** Return a RUN command for installing/checking build dependencies. */
  protected abstract String generateRunForInstallingDeps();

  /** Return the default compile commands for the C docker container. */
  protected abstract List<String> defaultBuildCommands();

  /** Return the commands used to build */
  protected List<String> getBuildCommands() {
    if (context.getTargetConfig().isSupported(BuildCommandsProperty.INSTANCE)) {
      var customBuildCommands = context.getTargetConfig().get(BuildCommandsProperty.INSTANCE);
      if (customBuildCommands != null && !customBuildCommands.isEmpty()) {
        return customBuildCommands;
      }
    }
    return defaultBuildCommands();
  }

  /** Return the command that sources the pre-build script, if there is one. */
  protected List<String> getPreBuildCommand() {
    var script = context.getTargetConfig().get(DockerProperty.INSTANCE).preBuildScript();
    if (!script.isEmpty()) {
      return List.of("source src-gen/" + StringEscapeUtils.escapeXSI(script));
    }
    return List.of();
  }

  /** Return the command that sources the post-build script, if there is one. */
  protected List<String> getPostBuildCommand() {
    var script = context.getTargetConfig().get(DockerProperty.INSTANCE).postBuildScript();
    if (!script.isEmpty()) {
      return List.of("source src-gen/" + StringEscapeUtils.escapeXSI(script));
    }
    return List.of();
  }

  /** Generate a header to print at the top of the Dockerfile. */
  protected String generateHeader() {
    return """
           # Generated by the Lingua Franca compiler version %s
           #   - Docs: https://www.lf-lang.org/docs/handbook/containerized-execution"
           """
        .formatted(LocalStrings.VERSION);
  }

  /** Return the Docker RUN command used for building. */
  protected String generateRunForBuild() {
    return "RUN "
        + StringUtil.joinObjects(
            Stream.of(
                    List.of("set -ex"),
                    getPreBuildCommand(),
                    getBuildCommands(),
                    getPostBuildCommand())
                .flatMap(java.util.Collection::stream)
                .collect(Collectors.toList()),
            " \\\n\t&& ");
  }

  /** Return the ENTRYPOINT command. */
  protected String generateEntryPoint() {
    return "ENTRYPOINT ["
        + getEntryPointCommands().stream()
            .map(cmd -> "\"" + cmd + "\"")
            .collect(Collectors.joining(","))
        + "]";
  }

  /** Return a COPY command to copy the executable from the builder to the runner. */
  protected String generateCopyOfExecutable() {
    var lfModuleName = context.getFileConfig().name;
    // safe because context.getFileConfig().name never contains spaces
    return "COPY --from=builder /lingua-franca/%s/bin/%s ./bin/%s"
        .formatted(lfModuleName, lfModuleName, lfModuleName);
  }

  /** Return a COPY command to copy the scripts from the builder to the runner. */
  protected String generateCopyOfScript() {
    var script = context.getTargetConfig().get(DockerProperty.INSTANCE).preRunScript();
    if (!script.isEmpty()) {
      return "COPY --from=builder /lingua-franca/%s/src-gen/%s ./scripts/"
          .formatted(context.getFileConfig().name, StringEscapeUtils.escapeXSI(script));
    }
    return "# (No pre-run script provided.)";
  }

  /**
   * Return a list of strings used to construct and entrypoint. If this is done for a federate, then
   * also include additional parameters to pass in the federation ID.
   */
  protected List<String> entryPoint() {
    if (context instanceof SubContext) {
      return Stream.concat(defaultEntryPoint().stream(), List.of("-i", "1").stream()).toList();
    } else {
      return defaultEntryPoint();
    }
  }

  /**
   * Return a list of commands to be used to construct an ENTRYPOINT, taking into account the
   * existence of a possible pre-run script.
   */
  protected final List<String> getEntryPointCommands() {
    var script = context.getTargetConfig().get(DockerProperty.INSTANCE).preRunScript();
    if (!script.isEmpty()) {
      return List.of(
          DockerOptions.DEFAULT_SHELL,
          "-c",
          "source scripts/"
              + script
              + " && "
              + entryPoint().stream().collect(Collectors.joining(" ")));
    }
    return entryPoint();
  }

  /** The default list of commands to construct an ENTRYPOINT out of. Different for each target. */
  public abstract List<String> defaultEntryPoint();

  /** Return the default base image. */
  public abstract String defaultImage();

  /** Return the base image to be used during the building stage. */
  protected String builderBase() {
    return baseImage(
        context.getTargetConfig().get(DockerProperty.INSTANCE).builderBase(), defaultImage());
  }

  /** Return the base image to be used during the running stage. */
  protected String runnerBase() {
    return baseImage(
        context.getTargetConfig().get(DockerProperty.INSTANCE).runnerBase(),
        baseImage(
            context.getTargetConfig().get(DockerProperty.INSTANCE).builderBase(), defaultImage()));
  }

  /** Return the selected base image, or the default one if none was selected. */
  private String baseImage(String name, String defaultImage) {
    if (name != null && !name.isEmpty()) {
      return name;
    }
    return defaultImage;
  }

  /**
   * Produce a DockerData object, which bundles all information needed to output a Dockerfile.
   *
   * @return docker data created based on the context in this instance
   */
  public DockerData generateDockerData() {
    return generateDockerData(context.getFileConfig().getSrcGenPath());
  }

  /**
   * Return a new {@code DockerData} object that can be used to generate a Dockerfile in the
   * directory indicated by the given path.
   *
   * @param path The directory in which to place the generated Dockerfile.
   */
  public DockerData generateDockerData(Path path) {
    var name = context.getFileConfig().name;
    var dockerFileContent = generateDockerFileContent();
    return new DockerData(name, path.resolve("Dockerfile"), dockerFileContent, context);
  }

  public static DockerGenerator dockerGeneratorFactory(LFGeneratorContext context) {
    var target = context.getTargetConfig().target;
    return switch (target) {
      case C, CCPP -> new CDockerGenerator(context);
      case TS -> new TSDockerGenerator(context);
      case Python -> new PythonDockerGenerator(context);
      case CPP, Rust ->
          throw new IllegalArgumentException("No Docker support for " + target + " yet.");
    };
  }
}
