/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package tests.integration.com.microsoft.azure.sdk.iot.iothubservices;

import com.microsoft.azure.sdk.iot.deps.util.Base64;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.service.RegistryManager;
import com.microsoft.azure.sdk.iot.service.devicetwin.DeviceMethod;
import com.microsoft.azure.sdk.iot.service.devicetwin.MethodResult;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubException;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubGatewayTimeoutException;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubNotFoundException;
import org.junit.*;
import tests.integration.com.microsoft.azure.sdk.iot.helpers.DeviceEmulator;
import tests.integration.com.microsoft.azure.sdk.iot.helpers.DeviceTestManager;
import tests.integration.com.microsoft.azure.sdk.iot.helpers.Tools;
import tests.integration.com.microsoft.azure.sdk.iot.helpers.X509Cert;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Integration E2E test for Device Method on the service client.
 */
public class DeviceMethodIT
{
    private static final String IOT_HUB_CONNECTION_STRING_ENV_VAR_NAME = "IOTHUB_CONNECTION_STRING";
    private static String iotHubConnectionString = "";
    private static String publicKeyCert;
    private static String privateKey;
    private static String x509Thumbprint;

    private static DeviceMethod methodServiceClient;

    private static final int MAX_DEVICES = 1;

    private static final String DEVICE_ID_NAME = "E2EJavaMethodMqtt";

    private static final Long RESPONSE_TIMEOUT = TimeUnit.SECONDS.toSeconds(200);
    private static final Long CONNECTION_TIMEOUT = TimeUnit.SECONDS.toSeconds(5);
    private static final String PAYLOAD_STRING = "This is a valid payload";

    private static List<DeviceTestManager> devices = new LinkedList<>();
    private static DeviceTestManager x509DeviceMQTT;

    private static final int NUMBER_INVOKES_PARALLEL = 10;
    private static final int INTERTEST_GUARDIAN_DELAY_MILLISECONDS = 2000;

    @BeforeClass
    public static void setUp() throws IotHubException, IOException, URISyntaxException, InterruptedException, GeneralSecurityException
    {
        iotHubConnectionString = Tools.retrieveEnvironmentVariableValue(IOT_HUB_CONNECTION_STRING_ENV_VAR_NAME);
        X509Cert cert = new X509Cert(0, false, "TestLeaf", "TestRoot");
        privateKey =  cert.getPrivateKeyLeafPem();
        publicKeyCert = cert.getPublicCertLeafPem();
        x509Thumbprint = cert.getThumbPrintLeaf();

        methodServiceClient = DeviceMethod.createFromConnectionString(iotHubConnectionString);

        RegistryManager registryManager = RegistryManager.createFromConnectionString(iotHubConnectionString);

        for (int i = 0; i < MAX_DEVICES; i++)
        {
            devices.add(new DeviceTestManager(registryManager, DEVICE_ID_NAME.concat("-" + i), IotHubClientProtocol.MQTT));
        }

        x509DeviceMQTT = new DeviceTestManager(registryManager, DEVICE_ID_NAME.concat("mqtt-x509"), IotHubClientProtocol.MQTT, publicKeyCert, privateKey, x509Thumbprint);
    }

    @Before
    public void cleanToStart()
    {
        for (DeviceTestManager device:devices)
        {
            device.clearDevice();
        }

        x509DeviceMQTT.clearDevice();
    }

    @After
    public void delayTests()
    {
        try
        {
            Thread.sleep(INTERTEST_GUARDIAN_DELAY_MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    protected static class RunnableInvoke implements Runnable
    {
        private String deviceId;
        private String testName;
        private CountDownLatch latch;
        private MethodResult result = null;
        private DeviceMethod methodServiceClient;

        private Exception exception = null;

        RunnableInvoke(DeviceMethod methodServiceClient, String deviceId, String testName, CountDownLatch latch)
        {
            this.methodServiceClient = methodServiceClient;
            this.deviceId = deviceId;
            this.testName = testName;
            this.latch = latch;
        }

        @Override
        public void run()
        {
            // Arrange
            exception = null;

            // Act
            try
            {
                result = methodServiceClient.invoke(deviceId, DeviceEmulator.METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, testName);
            }
            catch (Exception e)
            {
                exception = e;
            }

            latch.countDown();
        }

        String getExpectedPayload()
        {
            return DeviceEmulator.METHOD_LOOPBACK + ":" + testName;
        }

        MethodResult getResult()
        {
            return result;
        }

        Exception getException()
        {
            return exception;
        }
    }

    @AfterClass
    public static void tearDown() throws Exception
    {
        for (DeviceTestManager device:devices)
        {
            device.stop();
        }

        if (x509DeviceMQTT != null)
        {
            x509DeviceMQTT.stop();
        }
    }

    @Test
    public void invokeMethodSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_LOOPBACK + ":" + PAYLOAD_STRING, result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodInvokeParallelSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);
        CountDownLatch cdl = new CountDownLatch(NUMBER_INVOKES_PARALLEL);
        List<RunnableInvoke> runs = new LinkedList<>();

        for (int i = 0; i < NUMBER_INVOKES_PARALLEL; i++)
        {
            RunnableInvoke runnableInvoke = new RunnableInvoke(methodServiceClient, deviceTestManger.getDeviceId(), "Thread" + i, cdl);
            new Thread(runnableInvoke).start();
            runs.add(runnableInvoke);
        }

        cdl.await();

        for (RunnableInvoke run:runs)
        {
            MethodResult result = run.getResult();
            assertNotNull((run.getException() == null ? "Runnable returns null without exception information" : run.getException().getMessage()), result);
            assertEquals((long)DeviceEmulator.METHOD_SUCCESS,(long)result.getStatus());
            assertEquals(run.getExpectedPayload(), result.getPayload().toString());
        }
    }

    @Test
    public void invokeMethodStandardTimeoutSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_LOOPBACK, null, null, PAYLOAD_STRING);
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_LOOPBACK + ":" + PAYLOAD_STRING, result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodNullPayloadSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, null);
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_LOOPBACK + ":null", result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodNumberSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, "100");
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS + ":succeed", result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodThrowsNumberFormatExceptionFailed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_THROWS, (long)result.getStatus());
        assertEquals("java.lang.NumberFormatException: For input string: \"" + PAYLOAD_STRING + "\"", result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodUnknownFailed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_UNKNOWN, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_NOT_DEFINED, (long)result.getStatus());
        assertEquals("unknown:" + DeviceEmulator.METHOD_UNKNOWN, result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodRecoverFromTimeoutSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        try
        {
            methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS, (long)5, CONNECTION_TIMEOUT, "7000");
            assert true;
        }
        catch(IotHubGatewayTimeoutException expected)
        {
            //Don't do anything. Expected throw.
        }

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, "100");
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS + ":succeed", result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodDefaultResponseTimeoutSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS, null, CONNECTION_TIMEOUT, "100");
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS + ":succeed", result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test
    public void invokeMethodDefaultConnectionTimeoutSucceed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, null, "100");
        deviceTestManger.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS + ":succeed", result.getPayload());
        assertEquals(0, deviceTestManger.getStatusError());
    }

    @Test (expected = IotHubGatewayTimeoutException.class)
    public void invokeMethodResponseTimeoutFailed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_DELAY_IN_MILLISECONDS, (long)5, CONNECTION_TIMEOUT, "7000");
    }

    @Test (expected = IotHubNotFoundException.class)
    public void invokeMethodUnknownDeviceFailed() throws Exception
    {
        // Arrange

        // Act
        MethodResult result = methodServiceClient.invoke("UnknownDevice", DeviceEmulator.METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);
    }

    @Test
    public void invokeMethodResetDeviceFailed() throws Exception
    {
        // Arrange
        DeviceTestManager deviceTestManger = devices.get(0);

        // Act
        try
        {
            MethodResult result = methodServiceClient.invoke(deviceTestManger.getDeviceId(), DeviceEmulator.METHOD_RESET, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, null);
            deviceTestManger.restartDevice();
            throw new Exception("Reset device do not affect the method invoke on the service");
        }
        catch (IotHubNotFoundException expected)
        {
            // Don't do anything, expected throw.
        }
        deviceTestManger.restartDevice();
    }

    @Test
    public void invokeMethodUsingMQTTX509Succeed() throws IOException, InterruptedException, IotHubException
    {
        // Act
        MethodResult result = methodServiceClient.invoke(x509DeviceMQTT.getDeviceId(), DeviceEmulator.METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);
        x509DeviceMQTT.waitIotHub(1, 10);

        // Assert
        assertNotNull(result);
        assertEquals((long)DeviceEmulator.METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(DeviceEmulator.METHOD_LOOPBACK + ":" + PAYLOAD_STRING, result.getPayload());
        assertEquals(0, x509DeviceMQTT.getStatusError());
    }
}
