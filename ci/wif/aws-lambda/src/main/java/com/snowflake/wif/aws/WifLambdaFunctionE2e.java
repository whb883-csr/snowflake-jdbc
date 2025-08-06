package com.snowflake.wif.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.time.Duration;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class WifLambdaFunctionE2e implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            context.getLogger().log("=== WIF AWS Lambda Function E2E started ===");
            
            cleanupTmpDirectory(context);
            
            validateQueryParameters(request);
            setSystemProperties(request);

            String branch = request.getQueryStringParameters().get("BRANCH");
            String tarballUrl = buildTarballUrl(branch);

            downloadAndExtractRepository(tarballUrl, context);
            String repoFolderPath = findRepositoryFolder(context);
            int mavenExitCode = executeMavenBuild(repoFolderPath, context);

            return createResponse(mavenExitCode);
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return createErrorResponse(500, "Error: " + e.getMessage());
        }
    }
    
    private void cleanupTmpDirectory(Context context) {
        try {
            File tmpDir = new File("/tmp");
            context.getLogger().log("Cleaning up /tmp directory...");
            
            File[] files = tmpDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().startsWith("wif-function-") || 
                        file.getName().startsWith("maven-repo-") ||
                        file.getName().startsWith("workspace-") ||
                        file.getName().startsWith("maven-home-")) {
                        
                        deleteRecursively(file, context);
                    }
                }
            }
            context.getLogger().log("Cleanup completed");
        } catch (Exception e) {
            context.getLogger().log("Cleanup warning: " + e.getMessage());
        }
    }
    
    private void deleteRecursively(File file, Context context) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child, context);
                }
            }
        }
        if (file.delete()) {
            context.getLogger().log("Deleted: " + file.getName());
        }
    }
    
    private void validateQueryParameters(APIGatewayProxyRequestEvent request) {
        Map<String, String> queryParams = request.getQueryStringParameters();
        if (queryParams == null) {
            throw new IllegalArgumentException("Missing query parameters");
        }

        String wifHost = queryParams.get("SNOWFLAKE_TEST_WIF_HOST");
        String wifAccount = queryParams.get("SNOWFLAKE_TEST_WIF_ACCOUNT");
        String wifProvider = queryParams.get("SNOWFLAKE_TEST_WIF_PROVIDER");
        String branch = queryParams.get("BRANCH");

        if (wifHost == null) {
            throw new IllegalArgumentException("Missing required query parameter: SNOWFLAKE_TEST_WIF_HOST");
        }
        if (wifAccount == null) {
            throw new IllegalArgumentException("Missing required query parameter: SNOWFLAKE_TEST_WIF_ACCOUNT");
        }
        if (wifProvider == null) {
            throw new IllegalArgumentException("Missing required query parameter: SNOWFLAKE_TEST_WIF_PROVIDER");
        }
        if (branch == null) {
            throw new IllegalArgumentException("Missing required query parameter: BRANCH");
        }
    }
    
    private void setSystemProperties(APIGatewayProxyRequestEvent request) {
        Map<String, String> queryParams = request.getQueryStringParameters();
        String wifHost = queryParams.get("SNOWFLAKE_TEST_WIF_HOST");
        String wifAccount = queryParams.get("SNOWFLAKE_TEST_WIF_ACCOUNT");
        String wifProvider = queryParams.get("SNOWFLAKE_TEST_WIF_PROVIDER");
        String branch = queryParams.get("BRANCH");
        
        System.setProperty("SNOWFLAKE_TEST_WIF_HOST", wifHost);
        System.setProperty("SNOWFLAKE_TEST_WIF_ACCOUNT", wifAccount);
        System.setProperty("SNOWFLAKE_TEST_WIF_PROVIDER", wifProvider);
        System.setProperty("BRANCH", branch);
    }
    
    private String buildTarballUrl(String branch) {
        if (Pattern.matches("^PR-\\d+$", branch)) {
            String prNumber = branch.substring(3);
            return "https://github.com/snowflakedb/snowflake-jdbc/archive/refs/pull/" + prNumber + "/head.tar.gz";
        } else {
            return "https://github.com/snowflakedb/snowflake-jdbc/archive/refs/heads/" + branch + ".tar.gz";
        }
    }

    private void downloadAndExtractRepository(String tarballUrl, Context context) throws IOException, InterruptedException {
        // Extract directly to /tmp (Lambda's writable directory)
        String workDir = "/tmp/wif-function-" + System.currentTimeMillis();
        File workingDirectory = new File(workDir);

        if (!workingDirectory.exists()) {
            workingDirectory.mkdirs();
        }

        context.getLogger().log("Extracting directly to /tmp: " + workingDirectory.getAbsolutePath());
        context.getLogger().log("Downloading using Java HTTP client: " + tarballUrl);
        
        try {
            downloadAndExtractWithJava(tarballUrl, workingDirectory, context);
            context.getLogger().log("Download and extraction completed successfully");
            
            // Specifically fix mvnw permissions
            fixMvnwPermissions(workingDirectory, context);
            
            return;
        } catch (Exception e) {
            throw new RuntimeException("Failed to download and extract: " + e.getMessage(), e);
        }
    }
    
    private void downloadAndExtractWithJava(String tarballUrl, File workingDirectory, Context context) throws IOException, InterruptedException {
        context.getLogger().log("Creating HTTP client...");
        
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
            
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tarballUrl))
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", "AWS-Lambda-Function")
            .GET()
            .build();
            
        context.getLogger().log("Sending HTTP request...");
        
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " when downloading " + tarballUrl);
            }
            
            context.getLogger().log("HTTP response received, extracting tar.gz...");
            
            try (InputStream responseStream = response.body();
                 GZIPInputStream gzipStream = new GZIPInputStream(responseStream);
                 TarArchiveInputStream tarStream = new TarArchiveInputStream(gzipStream)) {
                
                TarArchiveEntry entry;
                while ((entry = tarStream.getNextTarEntry()) != null) {
                    File outputFile = new File(workingDirectory, entry.getName());
                    
                    if (entry.isDirectory()) {
                        outputFile.mkdirs();
                    } else {
                        outputFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            tarStream.transferTo(fos);
                        }
                    }
                }
            }
            
            context.getLogger().log("Tar extraction completed");
            
        } catch (Exception e) {
            context.getLogger().log("Download/extract error: " + e.getMessage());
            throw new IOException("Failed to download and extract: " + e.getMessage(), e);
        }
    }
    
    private void fixMvnwPermissions(File workingDirectory, Context context) {
        try {
            context.getLogger().log("Searching for mvnw files to fix permissions...");
            
            // Search recursively for mvnw files
            findAndFixMvnw(workingDirectory, context);
            
        } catch (Exception e) {
            context.getLogger().log("Warning: Failed to fix mvnw permissions: " + e.getMessage());
        }
    }
    
    private void findAndFixMvnw(File directory, Context context) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findAndFixMvnw(file, context); // Recursive search
                } else if (file.getName().equals("mvnw")) {
                    // Found mvnw file - fix permissions
                    boolean wasExecutable = file.canExecute();
                    boolean success = file.setExecutable(true, false); // true for all users
                    
                    context.getLogger().log(String.format(
                        "Found mvnw: %s (was executable: %s, set executable: %s, now executable: %s)",
                        file.getAbsolutePath(),
                        wasExecutable,
                        success,
                        file.canExecute()
                    ));
                    
                    // Also try setting read permissions
                    file.setReadable(true, false);
                    file.setWritable(true, false);
                }
            }
        }
    }
    
    private String findRepositoryFolder(Context context) {
        // Look directly in /tmp for our extracted repository
        File[] tempDirs = new File("/tmp").listFiles();
        
        if (tempDirs != null) {
            for (File dir : tempDirs) {
                if (dir.isDirectory() && dir.getName().startsWith("wif-function-")) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isDirectory() && file.getName().startsWith("snowflake-jdbc-")) {
                                return file.getAbsolutePath();
                            }
                        }
                    }
                }
            }
        }
        
        throw new RuntimeException("Driver repository folder not found");
    }
    
    private int executeMavenBuild(String repoFolderPath, Context context) {
        Process process = null;
        try {
            // Use /tmp explicitly for all Maven directories
            File mavenRepo = new File("/tmp", "maven-repo-" + System.currentTimeMillis());
            File workspace = new File("/tmp", "workspace-" + System.currentTimeMillis());
            mavenRepo.mkdirs();
            workspace.mkdirs();

            ProcessBuilder pb = new ProcessBuilder(
                "bash", "-c",
                "cd " + repoFolderPath + " && " +
                "./mvnw -Dmaven.repo.local=" + mavenRepo.getAbsolutePath() + " " +
                "-DjenkinsIT " +
                "-Dnet.snowflake.jdbc.temporaryCredentialCacheDir=" + workspace.getAbsolutePath() + " " +
                "-Dnet.snowflake.jdbc.ocspResponseCacheDir=" + workspace.getAbsolutePath() + " " +
                "-Djava.io.tmpdir=" + workspace.getAbsolutePath() + " " +
                "-Djacoco.skip.instrument=true " +
                "-Dskip.unitTests=true " +
                "-DintegrationTestSuites=WIFTestSuite " +
                "-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn " +
                "-Dnot-self-contained-jar " +
                "-Dmaven.test.failure.ignore=false " +
                "-DMAVEN_OPTS=\"-Xmx1536m\" " +
                "-Dmaven.artifact.threads=1 " +
                "-Dmaven.compile.fork=false " +
                "verify --batch-mode --show-version --fail-fast --no-transfer-progress"
            );
            pb.redirectErrorStream(true);
            
            // Redirect all Maven directories to /tmp to avoid read-only filesystem issues
            String mavenHome = "/tmp/maven-home-" + System.currentTimeMillis();
            new File(mavenHome).mkdirs();
            
            context.getLogger().log("Setting Maven home to: " + mavenHome);
            
            pb.environment().put("MAVEN_USER_HOME", mavenHome);
            pb.environment().put("HOME", "/tmp");
            pb.environment().put("USER_HOME", "/tmp");
            pb.environment().put("MAVEN_OPTS", "-Duser.home=/tmp -Djava.io.tmpdir=/tmp");
            
            pb.environment().put("SNOWFLAKE_TEST_WIF_HOST", System.getProperty("SNOWFLAKE_TEST_WIF_HOST"));
            pb.environment().put("SNOWFLAKE_TEST_WIF_ACCOUNT", System.getProperty("SNOWFLAKE_TEST_WIF_ACCOUNT"));
            pb.environment().put("SNOWFLAKE_TEST_WIF_PROVIDER", System.getProperty("SNOWFLAKE_TEST_WIF_PROVIDER"));
            pb.environment().put("SF_ENABLE_EXPERIMENTAL_AUTHENTICATION", "true");

            process = pb.start();
            
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            
            Thread outputThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        context.getLogger().log(line);
                    }
                } catch (IOException e) {
                    context.getLogger().log("Error reading Maven output: " + e.getMessage());
                }
            });
            outputThread.start();
            
            // AWS Lambda has a maximum execution time, typically 15 minutes
            if (!process.waitFor(14, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return -2; // timeout
            }
            return process.exitValue();
            
        } catch (Exception e) {
            context.getLogger().log("Maven build error: " + e.getMessage());
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return -1;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
    
    private APIGatewayProxyResponseEvent createResponse(int mavenExitCode) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");
        response.setHeaders(headers);

        if (mavenExitCode == 0) {
            response.setStatusCode(200);
            response.setBody("WIF tests completed successfully");
        } else {
            String mavenResult;
            String testResults;
            
            if (mavenExitCode == -2) {
                mavenResult = "TIMEOUT (exit code: " + mavenExitCode + ")";
                testResults = "Build timed out after 14 minutes";
            } else {
                mavenResult = "FAILED (exit code: " + mavenExitCode + ")";
                testResults = "Build or tests failed";
            }
            
            String responseBody = String.format(
                    "MAVEN_RESULT=%s\nTEST_STATUS=%s",
                    mavenResult, testResults
            );
            response.setStatusCode(500);
            response.setBody(responseBody);
        }
        return response;
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");
        response.setHeaders(headers);
        response.setStatusCode(statusCode);
        response.setBody(message);
        return response;
    }
}