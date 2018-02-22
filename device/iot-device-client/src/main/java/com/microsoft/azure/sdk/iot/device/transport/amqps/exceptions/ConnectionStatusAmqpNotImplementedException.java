/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.device.transport.amqps.exceptions;

import com.microsoft.azure.sdk.iot.device.transport.ConnectionStatusExceptions.ProtocolConnectionStatusException;

/**
 * This exception is thrown when a amqp:not-implemented error is encountered over an AMQP connection
 *
 * See {@linktourl http://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-complete-v1.0-os.pdf}
 */
public class ConnectionStatusAmqpNotImplementedException extends ProtocolConnectionStatusException
{
    public static final String errorCode = "amqp:not-implemented";

    public ConnectionStatusAmqpNotImplementedException()
    {
        super();
    }

    public ConnectionStatusAmqpNotImplementedException(String message)
    {
        super(message);
    }

    public ConnectionStatusAmqpNotImplementedException(String message, Throwable cause) { super(message, cause); }

    public ConnectionStatusAmqpNotImplementedException(Throwable cause) { super(cause); }
}
