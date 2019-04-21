package jetbrains.buildServer.xldeploy.agent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildFinishedStatus;
import jetbrains.buildServer.agent.BuildProcess;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.agent.BuildRunnerContext;
import jetbrains.buildServer.xldeploy.agent.XldDeploymentPackage;
import jetbrains.buildServer.xldeploy.agent.XldCustomCharacterEscapeHandler;
import jetbrains.buildServer.xldeploy.common.XldPackageConstants;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import com.sun.xml.bind.marshaller.DataWriter;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class XldPackageBuildProcess implements BuildProcess {

    BuildProgressLogger logger;
    OkHttpClient client;
    String host;
    int port;
    String credential;
    String scheme;
    String applicationName;
    String versionName;
    String deployables;

    public XldPackageBuildProcess(AgentRunningBuild runningBuild, BuildRunnerContext context) throws RunBuildException {

        final Map<String, String> runnerParameters = context.getRunnerParameters();

        logger = runningBuild.getBuildLogger();
        logger.progressStarted("Progress started for XldPackageBuildProcess");

        client = new OkHttpClient();

        host = runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_HOST);
        port = Integer.parseInt(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_PORT));

        credential = Credentials.basic(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_USERNAME),
                runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_PASSWORD));

        scheme = runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_HTTPS) == null?"http":"https";

        XldDeploymentPackage dp = new XldDeploymentPackage();
        dp.setApplication(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_APPLICATION_NAME));
        dp.setVersion(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_VERSION_NAME));
        dp.setDeployables(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_DEPLOYABLES));
        dp.setTemplates(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_TEMPLATES));
        dp.setDependencyResolution(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_DEPENDENCY_RESOLUTION));
        dp.setApplicationDependencies(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_APPLICATION_DEPENDENCIES));
        dp.setBoundTemplates(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_BOUND_TEMPLATES));
        dp.setOrchestrator(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_ORCHESTRATOR));
        dp.setUndeployDependencies(runnerParameters.get(XldPackageConstants.SETTINGS_XLDPACKAGE_UNDEPLOY_DEPENDENCIES));

        packageDar(runningBuild, dp);

        logger.progressFinished();

	}

    @Override
    public void interrupt() {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isFinished() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isInterrupted() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void start() throws RunBuildException {
        // TODO Auto-generated method stub

    }

    @Override
    public BuildFinishedStatus waitFor() throws RunBuildException {
        // TODO Auto-generated method stub
        return null;
    }

    private void packageDar(AgentRunningBuild runningBuild, XldDeploymentPackage dp) throws RunBuildException {

/*
    For now, accept the deployables, templates, applicationDependencies, boundTemplates, and orchestrator in XML format.
    TO-DO:  Dynamically modify the view and edit JSPs to present types and properties as the user builds the package
    TO-DO:  Modularize this method.
    TO-DO:  Copy deployment artifacts into the deployment package structure.
*/

        File agentWorkDir = runningBuild.getWorkingDirectory();
        String agentWorkDirPath = agentWorkDir.getPath();
        long buildId = runningBuild.getBuildId();
        logger.message(agentWorkDirPath);
        File dpWorkDir = new File(String.format("%s/%d/deploymentPackage", agentWorkDirPath, buildId));
        dpWorkDir.mkdirs();
        String dpWorkDirPath = dpWorkDir.getPath();

        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(XldDeploymentPackage.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
            jaxbMarshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            try {
                PrintWriter printWriter = new PrintWriter(new File(String.format("%s/deployit-manifest.xml", dpWorkDirPath, buildId)));
                DataWriter dataWriter = new DataWriter(printWriter, "UTF-8", new XldCustomCharacterEscapeHandler());
                jaxbMarshaller.marshal(dp, dataWriter);
                printWriter.close();
            } catch (FileNotFoundException e) {
                logger.message("FileNotFoundException " + e);
                throw new RunBuildException(e);
            }
        } catch (JAXBException e) {
            logger.message("JAXBException " + e);
            throw new RunBuildException(e);
        }

        try {
            File fileToZip = new File(dpWorkDirPath);
            File[] children = fileToZip.listFiles();
            FileOutputStream fos = new FileOutputStream(String.format("%s/%s-%s.dar", dpWorkDirPath, dp.getApplication(), dp.getVersion()));
            ZipOutputStream zipOut = new ZipOutputStream(fos);
            for (File childFile : children) {
                zipFile(childFile, childFile.getName(), zipOut);
            }
            zipOut.close();
            fos.close();
        } catch (IOException e) {
            logger.message("IOException " + e);
            throw new RunBuildException(e);
        }

    }

    private void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }

}