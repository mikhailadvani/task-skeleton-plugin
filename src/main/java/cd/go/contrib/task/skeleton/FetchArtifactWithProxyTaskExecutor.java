/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cd.go.contrib.task.skeleton;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.thoughtworks.go.plugin.api.task.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

// TODO: execute your task and setup stdout/stderr to pipe the streams to GoCD
public class FetchArtifactWithProxyTaskExecutor {
    public static final String proxy_conf_file = "/etc/go/artifact_proxy.conf";
    private static final String tmpHeadersStoreFilename = "headers.txt";
    private final TaskConfig taskConfig;
    private final Context context;
    private final JobConsoleLogger console;
    private final Map environmentVariables;
    private Boolean success = true;
    private String message = "Downloaded artifact";

    public FetchArtifactWithProxyTaskExecutor(TaskConfig config, Context taskContext, JobConsoleLogger consoleLogger) {
        taskConfig = config;
        context = taskContext;
        console = consoleLogger;
        environmentVariables = this.context.getEnvironmentVariables();
    }

    public Result execute() {
        try {
            return fetchArtifact();
        } catch (Exception e) {
            return new Result(false, "Error making HTTP GET call", e);
        }
    }

    private Result fetchArtifact() throws IOException, InterruptedException  {
        GoStage stage = new GoStage(getEnvironmentVariable("GO_PIPELINE_NAME"),
                                    Integer.parseInt(getEnvironmentVariable("GO_PIPELINE_COUNTER")),
                                    getEnvironmentVariable("GO_STAGE_NAME"),
                                    Integer.parseInt(getEnvironmentVariable("GO_STAGE_COUNTER")));
        String fetchUrl = artifactDownloadUrl(stage);
        this.console.printLine("Downloading artifact from: " + fetchUrl);
        curl(fetchUrl, true, true, true);
        printCacheInformation();
        return new Result(this.success, this.message);
    }

    private void printCacheInformation() throws IOException {
        if( (new File(proxy_conf_file).isFile()) ) {
            HashMap<String, String> proxyFileConfigs = new HashMap<>();
            String[] configFileLines = new String(Files.readAllBytes(Paths.get(proxy_conf_file))).split("\n");
            for (String configFileLine : configFileLines) {
                String[] keyValue = configFileLine.split("=");
                proxyFileConfigs.put(keyValue[0].trim(), keyValue[1].trim());
            }
            String cacheHeader = proxyFileConfigs.get("CACHE_HEADER");
            String tmpHeadersStoreFileLocation = this.context.getWorkingDir() + "/" + tmpHeadersStoreFilename;
            if ( (new File(tmpHeadersStoreFileLocation).isFile())) {
                HashMap<String, String> responseHeaders = new HashMap<>();
                String[] responseHeadersFileLines = new String(Files.readAllBytes(Paths.get(tmpHeadersStoreFileLocation))).split("\n");
                for (String responseHeaderFileLine : responseHeadersFileLines) {
                    String[] keyValue = responseHeaderFileLine.split(":");
                    if (keyValue.length == 2) {
                        responseHeaders.put(keyValue[0].trim(), keyValue[1].trim());
                    }
                }
                this.console.printLine("Cache status:" + responseHeaders.get(cacheHeader));
            }
        }
    }
    private Set<GoStage> deduplicatedUpstreamPipelines(Set<GoStage> upstreamStages) {
        Iterator<GoStage> iterator = upstreamStages.iterator();
        this.console.printLine("!!!!Removing duplicate pipelines:!!!!");
        while (iterator.hasNext()) {
            GoStage stage = iterator.next();
            if (stage.greaterOrSameVersionAvailable(upstreamStages)) {
                this.console.printLine(stage.pipelineUrl());
                iterator.remove();
            }
        }
        this.console.printLine("!!!!!Removed duplicate pipelines!!!!!");
        return upstreamStages;
    }

    private String curl(String path, Boolean useProxy, Boolean saveFile, Boolean saveHeaders) throws IOException, InterruptedException {
        ProcessBuilder curl = createCurlCommand(path, useProxy, saveFile, saveHeaders);
        curl.environment().putAll(this.context.getEnvironmentVariables());
        Process curlProcess = curl.start();
        this.console.readErrorOf(curlProcess.getErrorStream());
        BufferedReader reader = new BufferedReader(new InputStreamReader(curlProcess.getInputStream()));
        String line;
        String output = "";
        while ((line = reader.readLine()) != null) {
            output = output + line;
        }
        curlProcess.waitFor();
        reader.close();
        curlProcess.destroy();
        return output;
    }

    private ProcessBuilder createCurlCommand(String path, Boolean useProxy, Boolean saveFile, Boolean saveHeaders) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add("curl");
        if (useProxy) { command.add(proxyUrl() + path); }
        else { command.add(goServerUrl() + path); }
        command.add("-s");
        if (saveFile) {
            command.add("-o");
            String destinationFilePath = this.context.getWorkingDir() + "/" + this.taskConfig.getDestination();
            command.add(destinationFilePath);
        }
        if (saveHeaders) {
            command.add("-D");
            String tmpHeadersStoreLocation = this.context.getWorkingDir() + "/" + tmpHeadersStoreFilename;
            command.add(tmpHeadersStoreLocation);
        }
        this.console.printLine("Going to make HTTP call to: " + String.join(" ", command));
        command.add("-u");
        command.add(getEnvironmentVariable("GO_SERVER_USERNAME") + ":" + getEnvironmentVariable("GO_SERVER_PASSWORD"));
        command.add("-k");

        return new ProcessBuilder(command);
    }

    private String proxyUrl() throws IOException {
        if( ! (new File(proxy_conf_file).isFile()) ) {
            this.console.printLine("Proxy config file " + proxy_conf_file + "not found. Continuing without proxy");
            return goServerUrl();
        }
        HashMap<String, String> proxyFileConfigs = new HashMap<>();
        String[] lines = new String(Files.readAllBytes(Paths.get(proxy_conf_file))).split("\n");
        for (String line : lines) {
            String[] keyValue = line.split("=");
            proxyFileConfigs.put(keyValue[0].trim(), keyValue[1].trim());
        }
        return proxyFileConfigs.get("ARTIFACT_FETCH_PROXY_HOST") + ":" + proxyFileConfigs.get("ARTIFACT_FETCH_PROXY_PORT");
    }

    private String goServerUrl() {
        StringBuilder urlBuilder = new StringBuilder();
        String goServerUrl = getEnvironmentVariable("GO_SERVER_URL");
        try {
            URL url = new URL(goServerUrl);
            urlBuilder.append(url.getProtocol());
            urlBuilder.append("://" + url.getHost());
            int port = url.getPort();
            if (port != -1) {
                urlBuilder.append(":" + port);
            }
            else {
                urlBuilder.append(":" + url.getDefaultPort());
            }
            return urlBuilder.toString();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return goServerUrl.replace("/go","");
    }

    private String getEnvironmentVariable(String variableName) {
        return this.environmentVariables.get(variableName).toString();
    }

    private Set<GoStage> getPipelineMaterials(GoStage currentStage, Set<GoStage> stages) throws IOException, InterruptedException {
        JsonParser jsonParser = new JsonParser();
        JsonArray material_revisions = jsonParser.parse(curl(currentStage.getPipelineUrlPath(),false, false, false)).getAsJsonObject()
                                                                            .get("build_cause").getAsJsonObject()
                                                                            .get("material_revisions").getAsJsonArray();

        for (JsonElement material_revision : material_revisions) {
            if (material_revision.getAsJsonObject().get("material").getAsJsonObject().get("type").getAsString().equals("Pipeline")) {
                GoStage upstreamStage = new GoStage(material_revision.getAsJsonObject().get("modifications").getAsJsonArray().get(0).getAsJsonObject().get("revision").getAsString());
                if (!upstreamStage.isIn(stages)) {
                    stages.add(upstreamStage);
                    stages.addAll(previousStagesInSamePipeline(upstreamStage));
                    stages.addAll(getPipelineMaterials(upstreamStage, stages));
                }
            }
        }
        return stages;
    }

    private Set<GoStage> previousStagesInSamePipeline(GoStage stage) throws IOException, InterruptedException {
        Set<GoStage> previousStages = new HashSet<GoStage>();
        JsonParser jsonParser = new JsonParser();
        JsonArray previousStagesJson = jsonParser.parse(curl(stage.getPipelineUrlPath(),false, false, false)).getAsJsonObject().get("stages").getAsJsonArray();
        for (JsonElement previous_stage_json : previousStagesJson) {
            GoStage previousStage = new GoStage(stage.getPipelineName(), stage.getPipelineCounter(), previous_stage_json.getAsJsonObject().get("name").getAsString(), Integer.parseInt(previous_stage_json.getAsJsonObject().get("counter").getAsString()));
            if (previousStage.isEqual(stage)) {
                return previousStages;
            }
            else {
                previousStages.add(previousStage);
            }
        }
        return previousStages;
    }

    private void printStages(Set<GoStage> stages) {
        for (GoStage stage : stages) {
            this.console.printLine(stage.pipelineUrl());
        }
    }

    private String artifactDownloadUrl(GoStage stage) throws IOException, InterruptedException {
        Set<GoStage> upstreamStages = new HashSet<GoStage>();
        upstreamStages.addAll(previousStagesInSamePipeline(stage));
        upstreamStages.addAll(deduplicatedUpstreamPipelines(getPipelineMaterials(stage, upstreamStages)));
        this.console.printLine("*******All upstream stages*******");
        printStages(upstreamStages);
        this.console.printLine("*******All upstream stages*******");
        GoStage targetStage = new GoStage(this.taskConfig.getPipelineName(), this.taskConfig.getStageName(), upstreamStages);
        if (targetStage.invalid()) {
            this.success = false;
            this.message = "Fetch artifact pipeline/stage not in upstream";
        }
        return "/go/files/" + targetStage.pipelineUrl() + "/" + this.taskConfig.getJobName() + "/" + this.taskConfig.getSource();
    }
}
