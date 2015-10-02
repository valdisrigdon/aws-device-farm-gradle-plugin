/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.devicefarm;

import static org.testng.Assert.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.testfixtures.ProjectBuilder;
import org.testng.annotations.Test;

import com.amazonaws.devicefarm.extension.DeviceFarmExtension;
import com.amazonaws.services.devicefarm.AWSDeviceFarmClient;
import com.amazonaws.services.devicefarm.model.DevicePool;
import com.amazonaws.services.devicefarm.model.ListDevicePoolsRequest;
import com.amazonaws.services.devicefarm.model.ListDevicePoolsResult;
import com.amazonaws.services.devicefarm.model.ListProjectsRequest;
import com.amazonaws.services.devicefarm.model.ListProjectsResult;
import com.amazonaws.services.devicefarm.model.Run;
import com.amazonaws.services.devicefarm.model.ScheduleRunRequest;
import com.amazonaws.services.devicefarm.model.ScheduleRunResult;
import com.amazonaws.services.devicefarm.model.TestType;
import com.amazonaws.services.devicefarm.model.Upload;
import com.amazonaws.services.devicefarm.model.UploadType;
import com.android.build.gradle.AppExtension;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Verifications;


public class DeviceFarmPluginTest {

    @Injectable
    AWSDeviceFarmClient apiMock;

    @Injectable
    DeviceFarmUploader uploaderMock;

    @Injectable
    Logger loggerMock;

    Project gradleProject = ProjectBuilder.builder().build();

    @Test
    public void deviceFarmPluginAddsTestServerToProject() {

        gradleProject.getPluginManager().apply("com.android.application");
        gradleProject.getPluginManager().apply("devicefarm");

        assertTrue(((AppExtension) gradleProject.getExtensions().findByName("android")).getTestServers().get(0)
                instanceof DeviceFarmServer);

    }

    @Test
    public void instrumentationTest(@Injectable File testPackage, @Injectable File testedApp) throws IOException {

        final ListProjectsResult projectList = new ListProjectsResult();
        final com.amazonaws.services.devicefarm.model.Project myProject = new com.amazonaws.services.devicefarm.model.Project()
                .withName("MyProject")
                .withArn("1234");

        projectList.setProjects(Arrays.asList(myProject));

        final ListDevicePoolsResult devicePoolList = new ListDevicePoolsResult();
        devicePoolList.setDevicePools(Arrays.asList(new DevicePool().withName("Top Devices").withArn("1234")));

        final DeviceFarmExtension extension = new DeviceFarmExtension(gradleProject);
        extension.setProjectName("MyProject");

        final DeviceFarmServer server = new DeviceFarmServer(extension, loggerMock, apiMock, uploaderMock, new DeviceFarmUtils(apiMock, extension));

        final ScheduleRunResult runResult = new ScheduleRunResult();
        runResult.setRun(new Run());
        runResult.getRun().setArn("arn:1:2:3:4:5:runarn/projarn");

        new Expectations() {{

            apiMock.listProjects(new ListProjectsRequest());
            result = projectList;

            apiMock.listDevicePools(new ListDevicePoolsRequest().withArn("1234"));
            result = devicePoolList;

            uploaderMock.upload((File) any, myProject, UploadType.INSTRUMENTATION_TEST_PACKAGE);
            result = new Upload().withArn("arn:instrumentation/test/pkg");

            uploaderMock.upload((File) any, myProject, UploadType.ANDROID_APP);
            result = new Upload().withArn("arn:android/app");

            apiMock.scheduleRun((ScheduleRunRequest) any);
            result = runResult;

        }};


        server.uploadApks("debug", testPackage, testedApp);

        new Verifications() {{

            ScheduleRunRequest runRequest = null;
            apiMock.scheduleRun(runRequest = withCapture());
            assertNotNull(runRequest);
            assertEquals(runRequest.getTest().getType(), TestType.INSTRUMENTATION.toString());


        }};



    }

}
