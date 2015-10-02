package com.amazonaws.devicefarm

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.devicefarm.AWSDeviceFarmClient
import com.amazonaws.services.devicefarm.model.ArtifactCategory
import com.amazonaws.services.devicefarm.model.ExecutionResult
import com.amazonaws.services.devicefarm.model.GetRunRequest
import com.amazonaws.services.devicefarm.model.GetRunResult
import com.amazonaws.services.devicefarm.model.ListArtifactsRequest
import com.amazonaws.services.devicefarm.model.ListJobsRequest
import com.amazonaws.services.devicefarm.model.ListSuitesRequest
import com.amazonaws.services.devicefarm.model.ListTestsRequest
import com.android.ddmlib.Log.LogLevel
import com.android.ddmlib.logcat.LogCatMessage
import com.google.common.base.Splitter
import com.google.gson.Gson
import com.madgag.gif.fmsware.AnimatedGifEncoder
import com.squareup.spoon.DeviceDetails
import com.squareup.spoon.DeviceResult
import com.squareup.spoon.DeviceTest
import com.squareup.spoon.DeviceTestResult
import com.squareup.spoon.DeviceTestResult.Status
import com.squareup.spoon.SpoonSummary
import com.squareup.spoon.html.HtmlRenderer
import groovy.xml.StreamingMarkupBuilder

class DeviceFarmToJUnit {

  public static void main(String[] args) {
    File outputDir = new File('/Users/valdis.rigdon/test/spoon/')
    outputDir.mkdirs()
    File junitXml = new File(outputDir, 'junit.xml')
    println new DeviceFarmToJUnit(getClient()).waitForTestRun("arn:aws:devicefarm:us-west-2:551118715052:run:9ffc6906-1689-4f25-bd12-a451223fa1ca/6723fd9c-bbfd-45b9-a59d-bac06653e777", junitXml, outputDir)
  }

  AWSDeviceFarmClient client
  File spoonIndexHtmlFile

  DeviceFarmToJUnit(AWSDeviceFarmClient api) {
    this.client = api
  }

  public void waitForTestRun(String runArn, File junitXmlFile, File outputDir) {
    GetRunResult runs = getRuns(runArn)
    def builder = new StreamingMarkupBuilder()
    def jobResult = [:]
    outputDir.mkdirs()
    def xml = builder.bind {
      def jobs = client.listJobs(new ListJobsRequest().withArn(runs.run.arn))
      def c = runs.run.counters
      // TODO: c.total includes the Setup Suite and Teardown Suite we are ignoring
      testsuites(errors: c.errored, tests: c.total, failures: c.failed, skipped: c.skipped, hostname: runs.run.name) {
        jobs.jobs.each { job ->
          def suites = client.listSuites(new ListSuitesRequest().withArn(job.arn))
          Map dtToDtr = [:]
          suites.suites.each { suite ->
            if (suite.name != 'Setup Suite' && suite.name != 'Teardown Suite') {
              c = suite.counters
              testsuite([name: "${suite.name} (${job.device.name})", errors: c.errored, tests: c.total, failures: c.failed, skipped: c.skipped, timestamp: suite.created.format("YYYY-MM-dd'T'HH:mm:ss")]) {
                def tests = client.listTests(new ListTestsRequest().withArn(suite.arn))
                tests.tests.each { test ->
                  def fileArtifacts = client.listArtifacts(new ListArtifactsRequest().withArn(test.arn).withType(ArtifactCategory.FILE))
                  String logcat = fileArtifacts.artifacts.find { it.name == 'Logcat' }.url.toURL().text
                  def logCatMessages = logcat.split('\n').collect {
                    // 10-02 13:58:32.970 17003 17003 D AndroidRuntime: Calling main entry com.android.commands.am.Am
                    Splitter splitter = Splitter.on(' ').limit(7).omitEmptyStrings()
                    def parts = splitter.splitToList(it)
                    if (parts.size() < 7) {
                      println it
                      return null
                    }
                    LogLevel level = LogLevel.getByLetterString(parts[4])
                    if (level == null) {
                      println it
                      return null
                    }
                    new LogCatMessage(level, parts[2], parts[3], 'Appian', parts[5].replace(':', ''), parts[0] + parts[1], parts[6])
                  }.findAll { it != null }
                  DeviceTest dt = new DeviceTest(suite.name, test.name)
                  int duration = 30
                  List<File> screenshots = new ArrayList<>()
                  def ssArtifacts = client.listArtifacts(new ListArtifactsRequest().withArn(test.arn).withType(ArtifactCategory.SCREENSHOT))
                  ssArtifacts.artifacts.each {
                    byte[] screenshot = it.url.toURL().bytes
                    def f = new File(outputDir, "/images/${it.name}.${it.extension}")
                    f.parentFile.mkdirs()
                    f.bytes = screenshot
                    screenshots << f
                  }
                  File gif = new File(outputDir, "/images/${test.name}.gif")
                  createAnimatedGif(screenshots, gif)
                  DeviceTestResult dtr = new DeviceTestResult(toStatus(test.result), null, duration, screenshots, gif, logCatMessages, new ArrayList<>())
                  dtToDtr.put(dt, dtr)
                  c = test.counters
                  testcase([classname: suite.name, name: test.name]) {
                    if (c.skipped == 1) {
                      skipped {
                      }
                    } else if (c.failed == 1) {
                      failure(message: test.message, type: 'Test Case failed.', logcat)
                    }
                  }
                }
              }
            }
          }
          DeviceDetails dd = new DeviceDetails(job.device.model, job.device.manufacturer, job.device.os, 42, 'en', 'US', false, job.device.name)
          DeviceResult dr = new DeviceResult(false, null, dd, dtToDtr, job.created.time, 60, new ArrayList<>())
          jobResult.put(job.device.name, dr)
        }
      }
    }
    renderJunitXml(junitXmlFile, xml.toString())
    def summary = new SpoonSummary(runs.run.name, null, System.currentTimeMillis(), 100, jobResult)
    renderSpoonRunnerOutput(outputDir, summary)
    spoonIndexHtmlFile = new File(outputDir, 'index.html')
  }

  private File renderJunitXml(File outputFile, String xml) {
    outputFile.parentFile.mkdirs()
    outputFile.text = xml
    outputFile
  }

  private File renderSpoonRunnerOutput(File outputDir, def summary) {
    HtmlRenderer html = new HtmlRenderer(summary, new Gson(), outputDir)
    html.render()
    new File(outputDir, 'index.html')
  }

  private def toStatus(String s) {
    def result = ExecutionResult.fromValue(s)
    if (result == ExecutionResult.PASSED) {
      return Status.PASS
    } else if (result == ExecutionResult.FAILED) {
      return Status.FAIL
    } else if (result == ExecutionResult.ERRORED) {
      return Status.ERROR
    }
  }

  private GetRunResult getRuns(String runArn) {
    GetRunResult result
    while (result == null || (result.run.totalJobs != result.run.completedJobs)) {
      if (result) {
        sleep(20_000)
      }
      result = client.getRun(new GetRunRequest().withArn(runArn))
    }
    result
  }

  static AWSDeviceFarmClient getClient() {
    AWSCredentials credentials = new BasicAWSCredentials('AKIAJ5YFNI5FMNLA2HZQ', 'RnSZ+LhFoLpcdzm4Nc3JhGhf5xqtdDnEpYnGw59A')

    final ClientConfiguration clientConfiguration = new ClientConfiguration()
      .withUserAgent(DeviceFarmToJUnit.class.name);

    AWSDeviceFarmClient apiClient = new AWSDeviceFarmClient(credentials, clientConfiguration);
    apiClient.setServiceNameIntern("devicefarm");
    return apiClient;
  }

  static void createAnimatedGif(List<File> testScreenshots, File animatedGif) throws IOException {
    AnimatedGifEncoder encoder = new AnimatedGifEncoder();
    encoder.start(animatedGif.getAbsolutePath());
    encoder.setDelay(1500 /* 1.5 seconds */);
    encoder.setQuality(1 /* highest */);
    encoder.setRepeat(0 /* infinite */);
    encoder.setTransparent(Color.WHITE);

    int width = 0;
    int height = 0;
    for (File testScreenshot : testScreenshots) {
      BufferedImage bufferedImage = ImageIO.read(testScreenshot);
      width = Math.max(bufferedImage.getWidth(), width);
      height = Math.max(bufferedImage.getHeight(), height);
    }
    encoder.setSize(width, height);

    for (File testScreenshot : testScreenshots) {
      encoder.addFrame(ImageIO.read(testScreenshot));
    }

    encoder.finish();
  }
}
