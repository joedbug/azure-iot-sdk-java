/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.deps.ws.impl.WebSocketImpl;
import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.transport.ConnectionStatusExceptions.ConnectionStatusException;
import com.microsoft.azure.sdk.iot.device.transport.ConnectionStatusExceptions.ProtocolConnectionStatusException;
import com.microsoft.azure.sdk.iot.device.transport.IotHubListener;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransportConnection;
import com.microsoft.azure.sdk.iot.device.transport.State;
import com.microsoft.azure.sdk.iot.device.transport.TransportUtils;
import com.microsoft.azure.sdk.iot.device.transport.amqps.exceptions.*;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.engine.*;
import org.apache.qpid.proton.engine.impl.TransportInternal;
import org.apache.qpid.proton.message.Message;
import org.apache.qpid.proton.reactor.FlowController;
import org.apache.qpid.proton.reactor.Handshaker;
import org.apache.qpid.proton.reactor.Reactor;
import org.apache.qpid.proton.reactor.ReactorOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * An AMQPS IotHub connection between a device and an IoTHub. This class contains functionality for sending/receiving
 * a message, and logic to re-establish the connection with the IoTHub in case it gets lost.
 */
public final class AmqpsIotHubConnection extends BaseHandler implements IotHubTransportConnection
{
    private static final int MAX_WAIT_TO_OPEN_CLOSE_CONNECTION = 1*60*1000; // 1 minute timeout
    private static final int MAX_WAIT_TO_TERMINATE_EXECUTOR = 30;
    private State state;

    private int linkCredit = -1;
    /** The {@link Delivery} tag. */
    private static final String WEB_SOCKET_PATH = "/$iothub/websocket";
    private static final String WEB_SOCKET_SUB_PROTOCOL = "AMQPWSB10";
    private static final int AMQP_PORT = 5671;
    private static final int AMQP_WEB_SOCKET_PORT = 443;

    private Connection connection;

    private String hostName;

    private final Boolean useWebSockets;
    private DeviceClientConfig deviceClientConfig;

    private Collection<IotHubListener> listeners;
    private ExecutorService executorService;

    private final ObjectLock openLock = new ObjectLock();
    private final ObjectLock closeLock = new ObjectLock();

    private Reactor reactor;

    private Boolean reconnectCall = false;
    private int currentReconnectionAttempt;
    private CustomLogger logger;

	public AmqpsSessionManager amqpsSessionManager;
    private final static String APPLICATION_PROPERTY_STATUS_CODE = "status-code";
    private final static String APPLICATION_PROPERTY_STATUS_DESCRIPTION = "status-description";

    /**
     * Constructor to set up connection parameters using the {@link DeviceClientConfig}.
     *
     * @param config The {@link DeviceClientConfig} corresponding to the device associated with this {@link com.microsoft.azure.sdk.iot.device.DeviceClient}.
     * @param currentReconnectionAttempt The current value of reconnection counter
     * @throws IOException if failed connecting to iothub.
     */
    public AmqpsIotHubConnection(DeviceClientConfig config, int currentReconnectionAttempt) throws IOException
    {
        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_001: [The constructor shall throw IllegalArgumentException if
        // any of the parameters of the configuration is null or empty.]
        if(config == null)
        {
            throw new IllegalArgumentException("The DeviceClientConfig cannot be null.");
        }
        if(config.getIotHubHostname() == null || config.getIotHubHostname().length() == 0)
        {
            throw new IllegalArgumentException("hostName cannot be null or empty.");
        }
        if (config.getDeviceId() == null || config.getDeviceId().length() == 0)
        {
            throw new IllegalArgumentException("deviceID cannot be null or empty.");
        }
        if(config.getIotHubName() == null || config.getIotHubName().length() == 0)
        {
            throw new IllegalArgumentException("hubName cannot be null or empty.");
        }
        if (config.getAuthenticationType() == DeviceClientConfig.AuthType.SAS_TOKEN)
        {
            if (config.getIotHubConnectionString().getSharedAccessKey() == null || config.getIotHubConnectionString().getSharedAccessKey().isEmpty())
            {
                if(config.getSasTokenAuthentication().getCurrentSasToken() == null || config.getSasTokenAuthentication().getCurrentSasToken().isEmpty())
                {
                    throw new IllegalArgumentException("Both deviceKey and shared access signature cannot be null or empty.");
                }
            }
        }

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_002: [The constructor shall save the configuration into private member variables.]
        this.deviceClientConfig = config;

        this.currentReconnectionAttempt = currentReconnectionAttempt;

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_017: [The constructor shall set the AMQP socket port using the configuration.]
        this.useWebSockets = this.deviceClientConfig.isUseWebsocket();
        if (useWebSockets)
        {
            this.hostName = String.format("%s:%d", this.deviceClientConfig.getIotHubHostname(), AMQP_WEB_SOCKET_PORT);
        }
        else
        {
            this.hostName = String.format("%s:%d", this.deviceClientConfig.getIotHubHostname(), AMQP_PORT);
        }

        this.logger = new CustomLogger(this.getClass());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_004: [The constructor shall initialize a new Handshaker
        // (Proton) object to handle communication handshake.]
        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_005: [The constructor shall initialize a new FlowController
        // (Proton) object to handle communication flow.]
        add(new Handshaker());
        add(new FlowController());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_006: [The constructor shall set its state to CLOSED.]
        this.state = State.CLOSED;

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_002: [The constructor shall create a Proton reactor.]
        try
        {
            this.reactor = createReactor();
        }
        catch (IOException e)
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_003: [The constructor shall throw IOException if the Proton reactor creation failed.]
            logger.LogError(e);
            throw new IOException("Could not create Proton reactor");
        }
        listeners = new ArrayList<>();

        logger.LogInfo("AmqpsIotHubConnection object is created successfully using port %s in %s method ", useWebSockets ? AMQP_WEB_SOCKET_PORT : AMQP_PORT, logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_001: [The constructor shall initialize the AmqpsSessionManager member variable with the given config.]
        this.amqpsSessionManager = new AmqpsSessionManager(this.deviceClientConfig);
    }

    /**
     * Creates a new DeviceOperation using the given configuration..
     *
     * @param deviceClientConfig the device configuration to add.
     */
    public void addDeviceOperationSession(DeviceClientConfig deviceClientConfig)
    {
        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_018: [The function shall do nothing if the deviceClientConfig parameter is null.]
        if (deviceClientConfig != null)
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_019: [The function shall call AmqpsSessionManager.addDeviceOperationSession with the given deviceClientConfig.]
            this.amqpsSessionManager.addDeviceOperationSession(deviceClientConfig);
        }
    }

    /**
     * Opens the {@link AmqpsIotHubConnection}.
     * <p>
     *     If the current connection is not open, this method
     *     will create a new SasToken. This method will
     *     start the {@link Reactor}, set the connection to open and make it ready for sending.
     * </p>
     *
     * @throws IOException If the reactor could not be initialized.
     */
    public void open() throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_007: [If the AMQPS connection is already open, the function shall do nothing.]
        if(this.state == State.CLOSED)
        {
            try
            {
                // Codes_SRS_AMQPSIOTHUBCONNECTION_15_009: [The function shall trigger the Reactor (Proton) to begin running.]
                openAsync();
            }
            catch(Exception e)
            {
                logger.LogError(e);
                // Codes_SRS_AMQPSIOTHUBCONNECTION_15_011: [If any exception is thrown while attempting to trigger
                // the reactor, the function shall close the connection and throw an IOException.]
                this.close();
                throw new IOException("Error opening Amqp connection: ", e);
            }

            // Codes_SRS_AMQPSIOTHUBCONNECTION_15_010: [The function shall wait for the reactor to be ready and for
            // enough link credit to become available.]
            try
            {
                // Codes_SRS_AMQPSIOTHUBCONNECTION_12_059: [The function shall call waitlock on openlock.]
                synchronized (openLock)
                {
                    this.openLock.waitLock(MAX_WAIT_TO_OPEN_CLOSE_CONNECTION);
                }

                // Codes_SRS_AMQPSIOTHUBCONNECTION_12_057: [The function shall call the connection to authenticate.]
                this.authenticate();

                // Codes_SRS_AMQPSIOTHUBCONNECTION_12_058: [The function shall call the connection to open device client links.]
                this.openLinks();
            }
            catch (InterruptedException e)
            {
                logger.LogError(e);
                throw new IOException("Waited too long for the connection to open.");
            }
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Private helper for open.
     * Starts the Proton reactor.
     */
    private void openAsync() throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        if (this.reactor == null)
        {
            this.reactor = createReactor();
        }

        if (executorService == null)
        {
            executorService = Executors.newFixedThreadPool(1);
        }

        IotHubReactor iotHubReactor = new IotHubReactor(reactor);
        ReactorRunner reactorRunner = new ReactorRunner(iotHubReactor);
        executorService.submit(reactorRunner);

        logger.LogInfo("Reactor is assigned to executor service, method name is %s ", logger.getMethodName());
    }

    /**
     * Starts the authentication by calling the AmqpsSessionManager.
     *
     * @throws IOException if authentication open throws.
     */
    public void authenticate() throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_020: [The function shall do nothing if the authentication is already open.]
        if (this.amqpsSessionManager.isAuthenticationOpened())
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_021: [The function shall call AmqpsSessionManager.authenticate.]
            this.amqpsSessionManager.authenticate();
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Opens all the operation links by calling the AmqpsSessionManager.
     *
     * @throws IOException if Proton throws.
     */
    public void openLinks() throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_022: [The function shall do nothing if the authentication is already open.]
        if (this.amqpsSessionManager.isAuthenticationOpened())
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_023: [The function shall call AmqpsSessionManager.openDeviceOperationLinks.]
            this.amqpsSessionManager.openDeviceOperationLinks();
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Closes the {@link AmqpsIotHubConnection}.
     * <p>
     *     If the current connection is not closed, this function
     *     will set the current state to closed and invalidate all connection related variables.
     * </p>
     *
     * @throws IOException if it failed closing the iothub connection.
     */
    public void close() throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        closeAsync();

        try
        {
            synchronized (closeLock)
            {
                closeLock.waitLock(MAX_WAIT_TO_OPEN_CLOSE_CONNECTION);
            }
        }
        catch (InterruptedException e)
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_004: [The function shall IOException throws if the waitLock throws.]
            logger.LogError(e);
            throw new IOException("Waited too long for the connection to close.");
        }

        if (this.executorService != null) {
            logger.LogInfo("Shutdown of executor service has started, method name is %s ", logger.getMethodName());
            this.executorService.shutdown();
            try
            {
                // Wait a while for existing tasks to terminate
                if (!this.executorService.awaitTermination(MAX_WAIT_TO_TERMINATE_EXECUTOR, TimeUnit.SECONDS)) {
                    this.executorService.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!this.executorService.awaitTermination(MAX_WAIT_TO_TERMINATE_EXECUTOR, TimeUnit.SECONDS)){
                        logger.LogInfo("Pool did not terminate");
                    }
                }
            }
            catch (InterruptedException ie)
            {
                // Codes_SRS_AMQPSIOTHUBCONNECTION_12_005: [The function shall throw IOException if the executor shutdown is interrupted.]
                logger.LogError(ie);
                // (Re-)Cancel if current thread also interrupted
                this.executorService.shutdownNow();
            }
            logger.LogInfo("Shutdown of executor service completed, method name is %s ", logger.getMethodName());
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Private helper for close.
     * Closes the AmqpsSessionManager, the connection and stops the Proton reactor.
     */
    private void closeAsync()
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_048 [If the AMQPS connection is already closed, the function shall do nothing.]
        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_012: [The function shall set the status of the AMQPS connection to CLOSED.]
        this.state = State.CLOSED;

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_013: [The function shall closeNow the AmqpsSessionManager and the AMQP connection.]
        this.amqpsSessionManager.closeNow();

        if (this.connection != null)
        {
            this.connection.close();
        }

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_014: [The function shall stop the Proton reactor.]

        this.reactor.stop();
        logger.LogInfo("Proton reactor has been stopped, method name is %s ", logger.getMethodName());

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Creates a binary message using the given content and messageId. Sends the created message using the sender link.
     *
     * @param message The message to be sent.
     * @param messageType the type of the message being sent
     * @param iotHubConnectionString the connection string to use for sender identification.
     * @throws IOException if send message fails
     * @return An {@link Integer} representing the hash of the message, or -1 if the connection is closed.
     */
    public synchronized Integer sendMessage(Message message, MessageType messageType, IotHubConnectionString iotHubConnectionString) throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        Integer deliveryHash = -1;

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_015: [If the state of the connection is CLOSED or there is not enough
        // credit, the function shall return -1.]
        if (this.state == State.CLOSED || this.linkCredit <= 0)
        {
            deliveryHash = -1;
        }
        else
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_024: [The function shall call AmqpsSessionManager.sendMessage with the given parameters.]
            deliveryHash = this.amqpsSessionManager.sendMessage(message, messageType, iotHubConnectionString);
        }

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_021: [The function shall return the delivery hash.]
        return deliveryHash;
    }

    /**
     * Sends the message result for the previously received message.
     *
     * @param message the message to be acknowledged.
     * @param result the message result (one of {@link IotHubMessageResult#COMPLETE},
     *               {@link IotHubMessageResult#ABANDON}, or {@link IotHubMessageResult#REJECT}).
     * @return a boolean true if sent message was received with success, or false on fail.
     */
    public synchronized Boolean sendMessageResult(AmqpsMessage message, IotHubMessageResult result)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        Boolean ackResult = false;
        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_022: [If the AMQPS Connection is closed, the function shall return false.]
        if(this.state != State.CLOSED)
        {
            try
            {
                logger.LogInfo("Acknowledgement for received message is %s, method name is %s ", result.name(), logger.getMethodName());
                // Codes_SRS_AMQPSIOTHUBCONNECTION_15_023: [If the message result is COMPLETE, ABANDON, or REJECT,
                // the function shall acknowledge the last message with acknowledgement type COMPLETE, ABANDON, or REJECT respectively.]
                switch (result)
                {
                    case COMPLETE:
                        message.acknowledge(AmqpsMessage.ACK_TYPE.COMPLETE);
                        break;
                    case REJECT:
                        message.acknowledge(AmqpsMessage.ACK_TYPE.REJECT);
                        break;
                    case ABANDON:
                        message.acknowledge(AmqpsMessage.ACK_TYPE.ABANDON);
                        break;
                    default:
                        // should never happen.
                        logger.LogError("Invalid IoT Hub message result (%s), method name is %s ", result.name(), logger.getMethodName());
                        throw new IllegalStateException("Invalid IoT Hub message result.");
                }

                // Codes_SRS_AMQPSIOTHUBCONNECTION_15_024: [The function shall return true after the message was acknowledged.]
                ackResult = true;
            }
            catch (Exception e)
            {
                // Codes_SRS_AMQPSIOTHUBCONNECTION_12_008: [The function shall return false if message acknowledge throws exception.]
                logger.LogError(e);
                //do nothing, since ackResult is already false
            }
        }
        return ackResult;
    }

    /**
     * Event handler for reactor init event.
     * @param event Proton Event object
     */
    @Override
    public void onReactorInit(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_033: [The event handler shall set the current handler to handle the connection events.]
        if(this.useWebSockets)
        {
            event.getReactor().connectionToHost(this.deviceClientConfig.getIotHubHostname(), AMQP_WEB_SOCKET_PORT, this);
        }
        else
        {
            event.getReactor().connectionToHost(this.deviceClientConfig.getIotHubHostname(), AMQP_PORT, this);
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for reactor final event. Releases the close lock.
     * If reconnection has been set starts the reconnection by calling openAsync()
     * @param event Proton Event object
     */
    @Override
    public void onReactorFinal(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_011: [The function shall call notify lock on close lock.]
        synchronized (closeLock)
        {
            closeLock.notifyLock();
        }

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_012: [The function shall set the reactor member variable to null.]
        this.reactor = null;

        if (reconnectCall)
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_013: [The function shall call openAsync and disable reconnection if it is a reconnection attempt.]
            reconnectCall = false;

            //TODO?
            //for (ServerListener listener : listeners)
            //{
            //    listener.reconnect();
            //}
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the connection init event
     * @param event The Proton Event object.
     */
    @Override
    public void onConnectionInit(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_025: [The event handler shall get the Connection (Proton) object from the event handler and set the host name on the connection.]
        this.connection = event.getConnection();
        this.connection.setHostname(this.hostName);

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_029: [The event handler shall open the connection.]
        this.connection.open();
        try
        {
            // Codes_SRS_AMQPSIOTHUBCONNECTION_12_009: [The event handler shall call the amqpsSessionManager.onConnectionInit function with the connection.]
            this.amqpsSessionManager.onConnectionInit(this.connection);
        }
        catch (IOException e)
        {
            logger.LogDebug("openLinks has thrown exception: %s", e.getMessage());
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the connection bound event. Sets Sasl authentication and proper authentication mode.
     * @param event The Proton Event object.
     */
    @Override
    public void onConnectionBound(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_030: [The event handler shall get the Transport (Proton) object from the event.]
        Transport transport = event.getConnection().getTransport();
        if(transport != null)
        {
            if (this.useWebSockets)
            {
                // Codes_SRS_AMQPSIOTHUBCONNECTION_25_049: [If websocket enabled the event handler shall configure the transport layer for websocket.]
                WebSocketImpl webSocket = new WebSocketImpl();
                webSocket.configure(this.hostName, WEB_SOCKET_PATH, 0, WEB_SOCKET_SUB_PROTOCOL, null, null);
                ((TransportInternal)transport).addTransportLayer(webSocket);
            }

            // Codes_SRS_AMQPSIOTHUBCONNECTION_15_031: [The event handler shall call the AmqpsSessionManager.onConnectionBound with the transport and the SSLContext.]
            this.amqpsSessionManager.onConnectionBound(transport);
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the connection unbound event. Sets the connection state to CLOSED.
     * @param event The Proton Event object.
     */
    @Override
    public void onConnectionUnbound(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_010: [The function sets the state to closed.]
        this.state = State.CLOSED;

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the delivery event. This method handles both sending and receiving a message.
     * @param event The Proton Event object.
     */
    @Override
    public void onDelivery(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        AmqpsMessage amqpsMessage = null;

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_015: [The function shall call AmqpsSessionManager.getMessageFromReceiverLink.]
        try
        {
            String linkName = event.getLink().getName();
            amqpsMessage = this.amqpsSessionManager.getMessageFromReceiverLink(linkName);

            if (amqpsMessage != null)
            {
                com.microsoft.azure.sdk.iot.device.Message message = this.convertFromProton(amqpsMessage, this.deviceClientConfig).getMessage();
                if (amqpsMessage.getApplicationProperties() != null && amqpsMessage.getApplicationProperties().getValue() != null)
                {
                    Map<String, Object> properties = amqpsMessage.getApplicationProperties().getValue();

                    if (properties.containsKey(APPLICATION_PROPERTY_STATUS_CODE))
                    {
                        int statusCode;
                        try
                        {
                            statusCode = Integer.valueOf(properties.get(APPLICATION_PROPERTY_STATUS_CODE).toString());
                            IotHubStatusCode iotHubStatusCode = IotHubStatusCode.getIotHubStatusCode(statusCode);

                            if (iotHubStatusCode == IotHubStatusCode.OK || iotHubStatusCode == IotHubStatusCode.OK_EMPTY)
                            {
                                //Codes_SRS_AMQPSIOTHUBCONNECTION_34_090: [If an amqp message can be received from the receiver link, and that amqp message contains a status code that is 200 or 204, this function shall notify this object's listeners that that message was received with a null exception.]
                                this.notifyListenersMessageReceived(message, null);
                            }
                            else
                            {
                                String statusDescription = "";
                                if (properties.containsKey(APPLICATION_PROPERTY_STATUS_DESCRIPTION))
                                {
                                    statusDescription = (String) properties.get(APPLICATION_PROPERTY_STATUS_DESCRIPTION);
                                }

                                //Codes_SRS_AMQPSIOTHUBCONNECTION_34_089: [If an amqp message can be received from the receiver link, and that amqp message contains a status code that is not 200 or 204, this function shall notify this object's listeners that that message was received and provide the status code's mapped exception.]
                                ConnectionStatusException connectionStatusException = IotHubStatusCode.getConnectionStatusException(iotHubStatusCode, statusDescription);

                                this.notifyListenersMessageReceived(message, connectionStatusException);
                            }
                        }
                        catch (NumberFormatException e)
                        {
                            logger.LogInfo("status code received from service could not be parsed to integer, method name is %s ", logger.getMethodName());
                            //Codes_SRS_AMQPSIOTHUBCONNECTION_34_093: [If an amqp message can be received from the receiver link, and that amqp message contains a status code, but that status code cannot be parsed to an integer, this function shall notify this object's listeners that that message was received with a null exception.]
                            this.notifyListenersMessageReceived(message, null);
                        }
                    }
                    else
                    {
                        //Codes_SRS_AMQPSIOTHUBCONNECTION_34_091: [If an amqp message can be received from the receiver link, and that amqp message contains no status code, this function shall notify this object's listeners that that message was received with a null exception.]
                        this.notifyListenersMessageReceived(message, null);
                    }
                }
                else
                {
                    //Codes_SRS_AMQPSIOTHUBCONNECTION_34_092: [If an amqp message can be received from the receiver link, and that amqp message contains no application properties, this function shall notify this object's listeners that that message was received with a null exception.]
                    this.notifyListenersMessageReceived(message, null);
                }
            }
            else
            {
                //Sender specific section for dispositions it receives
                if (event.getType() == Event.Type.DELIVERY)
                {
                    logger.LogInfo("Reading the delivery event in Sender link, method name is %s ", logger.getMethodName());
                    // Codes_SRS_AMQPSIOTHUBCONNECTION_15_038: [If this link is the Sender link and the event type is DELIVERY, the event handler shall get the Delivery (Proton) object from the event.]
                    Delivery d = event.getDelivery();
                    DeliveryState remoteState = d.getRemoteState();

                    // Codes_SRS_AMQPSIOTHUBCONNECTION_15_039: [The event handler shall note the remote delivery state and use it and the Delivery (Proton) hash code to inform the AmqpsIotHubConnection of the message receipt.]
                    boolean state = remoteState.equals(Accepted.getInstance());
                    logger.LogInfo("Is state of remote Delivery COMPLETE ? %s, method name is %s ", state, logger.getMethodName());
                    logger.LogInfo("Inform listeners that a message has been sent to IoT Hub along with remote state, method name is %s ", logger.getMethodName());

                    for (IotHubListener listener : this.listeners)
                    {
                        listener.onMessageSent(new com.microsoft.azure.sdk.iot.device.Message(""), null);
                        //TODO can only get hashcode from here. Transport layer only needs that hashcode to find the message to move from inProgress queue to sent queue
                        //Since AMQP is the only protocol we have that has this acknowledgement functionality, maybe change messageSent parameters to take an integer instead
                    }

                    // release the delivery object which created in sendMessage().
                    d.free();
                }
            }
        }
        catch (IOException e)
        {
            logger.LogDebug("onDelivery has thrown exception: %s", e.getMessage());
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the link init event. Sets the proper target address on the link.
     * @param event The Proton Event object.
     */
    @Override
    public void onLinkInit(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_016: [The function shall get the link from the event and call device operation objects with it.]
        Link link = event.getLink();
        try
        {
            this.amqpsSessionManager.onLinkInit(link);
        }
        catch (IOException e)
        {
            logger.LogDebug("Exception in onLinkInit: %s", e.getMessage());
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the link flow event. Handles sending a single message.
     * @param event The Proton Event object.
     */
    @Override
    public void onLinkFlow(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_040: [The event handler shall save the remaining link credit.]
        this.linkCredit = event.getLink().getCredit();
        logger.LogDebug("The link credit value is %s, method name is %s", this.linkCredit, logger.getMethodName());

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the link remote open event. This signifies that the
     * {@link org.apache.qpid.proton.reactor.Reactor} is ready, so we set the connection to OPEN.
     * @param event The Proton Event object.
     */
    @Override
    public void onLinkRemoteOpen(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_052: [The function shall call AmqpsSessionManager.onLinkRemoteOpen with the given link.]
        if (this.amqpsSessionManager.onLinkRemoteOpen(event))
        {
            this.state = State.OPEN;

            // Codes_SRS_AMQPSIOTHUBCONNECTION_99_001: [All server listeners shall be notified when that the connection has been established.]
            for (IotHubListener listener : this.listeners)
            {
                listener.onConnectionEstablished(null);
            }

            // Codes_SRS_AMQPSIOTHUBCONNECTION_21_051 [The open lock shall be notified when that the connection has been established.]
            synchronized (openLock)
            {
                openLock.notifyLock();
            }
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the link remote close event. This triggers reconnection attempts until successful.
     * Both sender and receiver links closing trigger this event, so we only handle one of them,
     * since the other is redundant.
     * @param event The Proton Event object.
     */
    @Override
    public void onLinkRemoteClose(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        this.state = State.CLOSED;

        if (event.getSender() != null && event.getSender().getRemoteCondition() != null && event.getSender().getRemoteCondition().getCondition() != null)
        {
            //Codes_SRS_AMQPSIOTHUBCONNECTION_34_061 [If the provided event object's transport holds a remote error condition object, this function shall report the associated ConnectionStatusException to this object's listeners.]
            String errorCode = event.getSender().getRemoteCondition().getCondition().toString();
            ConnectionStatusException connectionStatusException = AmqpsIotHubConnection.getConnectionStatusExceptionFromAMQPExceptionCode(errorCode);
            this.notifyListenersConnectionLost(connectionStatusException);
        }

        String linkName = event.getLink().getName();
        if (this.amqpsSessionManager.isLinkFound(linkName))
        {
            logger.LogInfo("Starting to reconnect to IotHub, method name is %s ", logger.getMethodName());
            // Codes_SRS_AMQPSIOTHUBCONNECTION_15_048: [The event handler shall attempt to startReconnect to IoTHub.]
            startReconnect();
        }

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Event handler for the transport error event. This triggers reconnection attempts until successful.
     * @param event The Proton Event object.
     */
    @Override
    public void onTransportError(Event event)
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        this.state = State.CLOSED;

        if (event.getTransport() != null && event.getTransport().getCondition() != null && event.getTransport().getCondition().getCondition() != null)
        {
            //Codes_SRS_AMQPSIOTHUBCONNECTION_34_060 [If the provided event object's transport holds an error condition object, this function shall report the associated ConnectionStatusException to this object's listeners.]
            String errorCode = event.getTransport().getCondition().getCondition().toString();
            ConnectionStatusException connectionStatusException = AmqpsIotHubConnection.getConnectionStatusExceptionFromAMQPExceptionCode(errorCode);
            this.notifyListenersConnectionLost(connectionStatusException);
        }

        logger.LogInfo("Starting to reconnect to IotHub, method name is %s ", logger.getMethodName());
        // Codes_SRS_AMQPSIOTHUBCONNECTION_15_048: [The event handler shall attempt to startReconnect to IoTHub.]
        startReconnect();

        logger.LogDebug("Exited from method %s", logger.getMethodName());
    }

    /**
     * Calls the AmqpsSessionManager to find the appropriate convertToProton converter.
     *
     * @param message the message to convert.
     * @return AmqpsConvertToProtonReturnValue containing the status and converted message.
     * @throws IOException if conversion fails.
     */
    protected AmqpsConvertToProtonReturnValue convertToProton(com.microsoft.azure.sdk.iot.device.Message message) throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_056: [The function shall call AmqpsSessionManager.convertToProton with the given message.]
        return this.amqpsSessionManager.convertToProton(message);
    }

    /**
     * Calls the AmqpsSessionManager to find the appropriate convertFromProton converter.
     *
     * @param amqpsMessage the message to convert.
     * @param deviceClientConfig the configuration to identify the message.
     * @return AmqpsConvertFromProtonReturnValue containing the status and converted message.
     * @throws IOException if conversion fails.
     */
    protected AmqpsConvertFromProtonReturnValue convertFromProton(AmqpsMessage amqpsMessage, DeviceClientConfig deviceClientConfig) throws IOException
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_056: [*The function shall call AmqpsSessionManager.convertFromProton with the given message. ]
        return this.amqpsSessionManager.convertFromProton(amqpsMessage, deviceClientConfig);
    }

    /**
     * Notifies all listeners that the connection was lost and attempts to startReconnect to the IoTHub
     * using an exponential backoff interval.
     */
    private void startReconnect()
    {
        logger.LogDebug("Entered in method %s", logger.getMethodName());

        reconnectCall = true;

        if (currentReconnectionAttempt == Integer.MAX_VALUE)
            currentReconnectionAttempt = 0;

        logger.LogInfo("Lost connection to the server. Reconnection attempt %s, method name is %s ", this.currentReconnectionAttempt, logger.getMethodName());
        try
        {
            Thread.sleep(TransportUtils.generateSleepInterval(this.currentReconnectionAttempt));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        closeAsync();
    }

    static ConnectionStatusException getConnectionStatusExceptionFromAMQPExceptionCode(String exceptionCode)
    {
        switch (exceptionCode)
        {
            case ConnectionStatusAmqpConnectionForcedException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_076: [The function shall map amqp exception code "amqp:connection:forced" to ConnectionStatusException "ConnectionStatusAmqpConnectionForcedException".]
                return new ConnectionStatusAmqpConnectionForcedException();
            case ConnectionStatusAmqpConnectionRedirectException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_078: [The function shall map amqp exception code "amqp:connection:redirect" to ConnectionStatusException "ConnectionStatusAmqpConnectionRedirectException".]
                return new ConnectionStatusAmqpConnectionRedirectException();
            case ConnectionStatusAmqpDecodeErrorException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_066: [The function shall map amqp exception code "amqp:decode-error" to ConnectionStatusException "ConnectionStatusAmqpDecodeErrorException".]
                return new ConnectionStatusAmqpDecodeErrorException();
            case ConnectionStatusAmqpDetachForcedException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_083: [The function shall map amqp exception code "amqp:link:detach-forced" to ConnectionStatusException "ConnectionStatusAmqpDetachForcedException".]
                return new ConnectionStatusAmqpDetachForcedException();
            case ConnectionStatusAmqpErrantLinkException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_080: [The function shall map amqp exception code "amqp:session:errant-link" to ConnectionStatusException "ConnectionStatusAmqpErrantLinkException".]
                return new ConnectionStatusAmqpErrantLinkException();
            case ConnectionStatusAmqpFrameSizeTooSmallException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_075: [The function shall map amqp exception code "amqp:frame-size-too-small" to ConnectionStatusException "ConnectionStatusAmqpFrameSizeTooSmallException".]
                return new ConnectionStatusAmqpFrameSizeTooSmallException();
            case ConnectionStatusAmqpFramingErrorException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_077: [The function shall map amqp exception code "amqp:connection:framing-error" to ConnectionStatusException "ConnectionStatusAmqpFramingErrorException".]
                return new ConnectionStatusAmqpFramingErrorException();
            case ConnectionStatusAmqpHandleInUseException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_081: [The function shall map amqp exception code "amqp:session:handle-in-use" to ConnectionStatusException "ConnectionStatusAmqpHandleInUseException".]
                return new ConnectionStatusAmqpHandleInUseException();
            case ConnectionStatusAmqpIllegalStateException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_074: [The function shall map amqp exception code "amqp:illegal-state" to ConnectionStatusException "ConnectionStatusAmqpIllegalStateException".]
                return new ConnectionStatusAmqpIllegalStateException();
            case ConnectionStatusAmqpInternalErrorException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_063: [The function shall map amqp exception code "amqp:internal-error" to ConnectionStatusException "ConnectionStatusAmqpInternalErrorException".]
                return new ConnectionStatusAmqpInternalErrorException();
            case ConnectionStatusAmqpInvalidFieldException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_069: [The function shall map amqp exception code "amqp:invalid-field" to ConnectionStatusException "ConnectionStatusAmqpInvalidFieldException".]
                return new ConnectionStatusAmqpInvalidFieldException();
            case ConnectionStatusAmqpLinkRedirectException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_086: [The function shall map amqp exception code "amqp:link:redirect" to ConnectionStatusException "ConnectionStatusAmqpLinkRedirectException".]
                return new ConnectionStatusAmqpLinkRedirectException();
            case ConnectionStatusAmqpLinkStolenException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_087: [The function shall map amqp exception code "amqp:link:stolen" to ConnectionStatusException "ConnectionStatusAmqpLinkStolenException".]
                return new ConnectionStatusAmqpLinkStolenException();
            case ConnectionStatusAmqpMessageSizeExceededException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_085: [The function shall map amqp exception code "amqp:link:message-size-exceeded" to ConnectionStatusException "ConnectionStatusAmqpMessageSizeExceededException".]
                return new ConnectionStatusAmqpMessageSizeExceededException();
            case ConnectionStatusAmqpNotAllowedException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_068: [The function shall map amqp exception code "amqp:not-allowed" to ConnectionStatusException "ConnectionStatusAmqpNotAllowedException".]
                return new ConnectionStatusAmqpNotAllowedException();
            case ConnectionStatusAmqpNotFoundException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_064: [The function shall map amqp exception code "amqp:not-found" to ConnectionStatusException "ConnectionStatusAmqpNotFoundException".]
                return new ConnectionStatusAmqpNotFoundException();
            case ConnectionStatusAmqpNotImplementedException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_070: [The function shall map amqp exception code "amqp:not-implemented" to ConnectionStatusException "ConnectionStatusAmqpNotImplementedException".]
                return new ConnectionStatusAmqpNotImplementedException();
            case ConnectionStatusAmqpPreconditionFailedException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_072: [The function shall map amqp exception code "amqp:precondition-failed" to ConnectionStatusException "ConnectionStatusAmqpPreconditionFailedException".]
                return new ConnectionStatusAmqpPreconditionFailedException();
            case ConnectionStatusAmqpResourceDeletedException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_073: [The function shall map amqp exception code "amqp:resource-deleted" to ConnectionStatusException "ConnectionStatusAmqpResourceDeletedException".]
                return new ConnectionStatusAmqpResourceDeletedException();
            case ConnectionStatusAmqpResourceLimitExceededException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_067: [The function shall map amqp exception code "amqp:resource-limit-exceeded" to ConnectionStatusException "ConnectionStatusAmqpResourceLimitExceededException".]
                return new ConnectionStatusAmqpResourceLimitExceededException();
            case ConnectionStatusAmqpResourceLockedException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_071: [The function shall map amqp exception code "amqp:resource-locked" to ConnectionStatusException "ConnectionStatusAmqpResourceLockedException".]
                return new ConnectionStatusAmqpResourceLockedException();
            case ConnectionStatusAmqpTransferLimitExceededException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_084: [The function shall map amqp exception code "amqp:link:transfer-limit-exceeded" to ConnectionStatusException "ConnectionStatusAmqpTransferLimitExceededException".]
                return new ConnectionStatusAmqpTransferLimitExceededException();
            case ConnectionStatusAmqpUnattachedHandleException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_082: [The function shall map amqp exception code "amqp:session:unattached-handle" to ConnectionStatusException "ConnectionStatusAmqpUnattachedHandleException".]
                return new ConnectionStatusAmqpUnattachedHandleException();
            case ConnectionStatusAmqpUnauthorizedAcessException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_065: [The function shall map amqp exception code "amqp:unauthorized-access" to ConnectionStatusException "ConnectionStatusAmqpUnauthorizedAcessException".]
                return new ConnectionStatusAmqpUnauthorizedAcessException();
            case ConnectionStatusAmqpWindowViolationException.errorCode:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_079: [The function shall map amqp exception code "amqp:session:window-violation" to ConnectionStatusException "ConnectionStatusAmqpWindowViolationException".]
                return new ConnectionStatusAmqpWindowViolationException();
            default:
                // Codes_SRS_AMQPSIOTHUBCONNECTION_34_088: [The function shall map all other amqp exception codes to the generic ConnectionStatusException "ProtocolConnectionStatusException".]
                return new ProtocolConnectionStatusException();
        }
    }

    /**
     * Class which runs the reactor.
     */
    private class ReactorRunner implements Callable
    {
        private final IotHubReactor iotHubReactor;

        ReactorRunner(IotHubReactor iotHubReactor)
        {
            this.iotHubReactor = iotHubReactor;
        }

        @Override
        public Object call()
        {
            iotHubReactor.run();
            return null;
        }
    }

    /**
     * Create a Proton reactor
     *
     * @return the Proton reactor
     * @throws IOException if Proton throws
     */
    private Reactor createReactor() throws IOException
    {
        if (this.deviceClientConfig.getAuthenticationType() == DeviceClientConfig.AuthType.X509_CERTIFICATE)
        {
            //Codes_SRS_AMQPSIOTHUBCONNECTION_34_053: [If the config is using x509 Authentication, the created Proton reactor shall not have SASL enabled by default.]
            ReactorOptions options = new ReactorOptions();
            options.setEnableSaslByDefault(false);
            return Proton.reactor(options, this);
        }
        else
        {
            return Proton.reactor(this);
        }
    }

    @Override
    public void addListener(IotHubListener listener) throws IOException
    {
        if (listener == null)
        {
            throw new IllegalArgumentException("listener cannot be null");
        }

        // Codes_SRS_AMQPSIOTHUBCONNECTION_12_054: [The function shall add the given listener to the listener list.]
        this.listeners.add(listener);
    }

    @Override
    public IotHubStatusCode sendMessage(com.microsoft.azure.sdk.iot.device.Message message) throws IOException
    {
        return null;
    }

    @Override
    public IotHubStatusCode sendMessageResult(com.microsoft.azure.sdk.iot.device.Message message, IotHubMessageResult result) throws IOException
    {
        return null;
    }

    private void notifyListenersMessageReceived(com.microsoft.azure.sdk.iot.device.Message message, Throwable throwable)
    {
        for (IotHubListener listener : this.listeners)
        {
            listener.onMessageReceived(message, throwable);
        }
    }

    private void notifyListenersConnectionLost(Throwable throwable)
    {
        for (IotHubListener listener : this.listeners)
        {
            listener.onConnectionLost(throwable);
        }
    }
}
