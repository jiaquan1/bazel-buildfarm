// Copyright 2021 The Bazel Authors. All rights reserved.
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

package build.buildfarm.worker;

import build.bazel.remote.execution.v2.ActionResult;
import build.buildfarm.worker.resources.ResourceLimits;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.rpc.Code;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

class DockerExecutor {
  private static final Logger logger = Logger.getLogger(DockerExecutor.class.getName());

  public static Code runActionWithDocker(
      Path execDir,
      ResourceLimits limits,
      Duration timeout,
      List<String> arguments,
      Map<String, String> envVars,
      ActionResult.Builder resultBuilder)
      throws InterruptedException {
    // construct docker client
    DockerClient dockerClient = DockerClientBuilder.getInstance().build();

    // get image
    System.out.println("Getting image");
    fetchImageIfMissing(dockerClient, limits.containerSettings.containerImage);

    // create container
    System.out.println("Creating container");
    String containerId = createContainer(dockerClient, execDir, limits, timeout, envVars);
    System.out.println("containerId: " + containerId);

    // start container
    System.out.println("start container");
    dockerClient.startContainerCmd(containerId).exec();

    // decide command to run
    System.out.println("create exec command");
    ExecCreateCmd execCmd = dockerClient.execCreateCmd(containerId);
    // execCmd.withCmd(arguments.toArray(new String[0]));
    for (int i = 0; i < arguments.size(); i++) {
      System.out.println("cliArg: " + arguments.get(i));
    }
    // execCmd.withCmd("/bin/bash","-c","cat /etc/os-release");
    // execCmd.withCmd("/bin/pwd");
    // execCmd.withCmd("/bin/ls /tmp");
    // execCmd.withCmd("/bin/bash","-c","ls -al /tmp/worker/shard/operations
    // /tmp/worker2/shard/operations");

    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("ls", "-al", execDir.toAbsolutePath().toString());
      Process process = builder.start();

      StringBuilder output = new StringBuilder();

      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line + "\n");
      }
      int exitVal = process.waitFor();
      System.out.println("ls peek: " + output);
    } catch (Exception e) {
    }

    execCmd.withCmd("/bin/bash", "-c", "ls -al " + execDir.toAbsolutePath().toString());
    execCmd.withAttachStderr(true);
    execCmd.withAttachStdout(true);
    String execId = execCmd.exec().getId();
    System.out.println("execId: " + execId);

    // execute command
    System.out.println("execute");
    ExecStartCmd execStartCmd = dockerClient.execStartCmd(execId);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    execStartCmd.exec(new ExecStartResultCallback(out, err)).awaitCompletion();
    System.out.println("stdout: " + out.toString());
    System.out.println("stderr: " + err.toString());

    // extract command exit code
    InspectExecCmd inspectExecCmd = dockerClient.inspectExecCmd(execId);
    InspectExecResponse response = inspectExecCmd.exec();
    System.out.println(response.getExitCodeLong());

    // build output
    resultBuilder.setExitCode(response.getExitCodeLong().intValue());
    resultBuilder.setStdoutRaw(ByteString.copyFromUtf8(out.toString()));
    resultBuilder.setStderrRaw(ByteString.copyFromUtf8(err.toString()));

    try {
      System.out.println("Cleanup container");
      dockerClient.removeContainerCmd(containerId).withRemoveVolumes(true).withForce(true).exec();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "couldn't shutdown container: ", e);
    }

    System.out.println("Done");
    return Code.OK;
  }

  private static String createContainer(
      DockerClient dockerClient,
      Path execDir,
      ResourceLimits limits,
      Duration timeout,
      Map<String, String> envVars)
      throws InterruptedException {

    CreateContainerCmd createContainerCmd =
        dockerClient.createContainerCmd(limits.containerSettings.containerImage);

    // prepare command
    // createContainerCmd.withCmd(arguments);
    // createContainerCmd.withCmd("sh","-c","pwd;ls");
    // createContainerCmd.withCmd("sleep", "5");
    createContainerCmd.withAttachStderr(true);
    createContainerCmd.withAttachStdout(true);
    createContainerCmd.withEnv(envMapToList(envVars));
    createContainerCmd.withNetworkDisabled(!limits.containerSettings.network);
    // createContainerCmd.withStopTimeout((int) timeout.getSeconds());

    String execDirStr = execDir.toAbsolutePath().toString();
    // createContainerCmd.withVolumes(new Volume("/tmp:/tmp"));

    System.out.println("execDirStr: " + execDirStr);
    createContainerCmd.withBinds(new Bind("/tmp", new Volume("/tmp"), true));
    createContainerCmd.withVolumes(new Volume("/tmp"));
    // createContainerCmd.withBinds(new Bind(execDirStr,new Volume(execDirStr)));
    // createContainerCmd.withBinds(new Bind("/tmp",new Volume("/tmp")));
    createContainerCmd.withWorkingDir(execDir.toAbsolutePath().toString());
    createContainerCmd.withTty(true);

    CreateContainerResponse response = createContainerCmd.exec();
    System.out.println("warnings: " + Arrays.toString(response.getWarnings()));
    return response.getId();
  }

  private static void fetchImageIfMissing(DockerClient dockerClient, String imageName)
      throws InterruptedException {
    // pull image if we don't already have it
    if (!isLocalImagePresent(dockerClient, imageName)) {
      dockerClient
          .pullImageCmd(imageName)
          .exec(new PullImageResultCallback())
          .awaitCompletion(1, TimeUnit.MINUTES);
    }
  }

  private static boolean isLocalImagePresent(DockerClient dockerClient, String imageName) {
    try {
      dockerClient.inspectImageCmd(imageName).exec();
    } catch (NotFoundException e) {
      return false;
    }
    return true;
  }

  private static List<String> envMapToList(Map<String, String> envVars) {
    List<String> envList = new ArrayList<>();
    for (Map.Entry<String, String> environmentVariable : envVars.entrySet()) {
      envList.add(environmentVariable.getKey() + "=" + environmentVariable.getValue());
    }

    return envList;
  }
}
