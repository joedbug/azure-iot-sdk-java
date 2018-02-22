/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.device.transport.amqps.exceptions;

import com.microsoft.azure.sdk.iot.device.transport.ConnectionStatusExceptions.ProtocolConnectionStatusException;

/**
 * This exception is thrown when a amqp:link:message-size-exceeded error is encountered over an AMQP connection
 *
 * See {@linktourl http://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-complete-v1.0-os.pdf}
 */
public class ConnectionStatusAmqpMessageSizeExceededException extends ProtocolConnectionStatusException
{
    public static final String errorCode = "amqp:link:message-size-exceeded";

    public ConnectionStatusAmqpMessageSizeExceededException()
    {
        super();
        this.isRetryable = true;
    }

    public ConnectionStatusAmqpMessageSizeExceededException(String message)
    {
        super(message);
        this.isRetryable = true;
    }

    public ConnectionStatusAmqpMessageSizeExceededException(String message, Throwable cause)
    {
        super(message, cause);
        this.isRetryable = true;
    }

    public ConnectionStatusAmqpMessageSizeExceededException(Throwable cause)
    {
        super(cause);
        this.isRetryable = true;
    }
}
