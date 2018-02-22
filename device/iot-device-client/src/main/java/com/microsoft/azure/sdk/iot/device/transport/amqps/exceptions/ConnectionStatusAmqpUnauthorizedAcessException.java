/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.device.transport.amqps.exceptions;

import com.microsoft.azure.sdk.iot.device.transport.ConnectionStatusExceptions.ProtocolConnectionStatusException;

/**
 * This exception is thrown when a amqp:unauthorized-access error is encountered over an AMQP connection
 *
 * See {@linktourl http://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-complete-v1.0-os.pdf}
 */
public class ConnectionStatusAmqpUnauthorizedAcessException extends ProtocolConnectionStatusException
{
    public static final String errorCode = "amqp:unauthorized-access";

    public ConnectionStatusAmqpUnauthorizedAcessException() { super(); }

    public ConnectionStatusAmqpUnauthorizedAcessException(String message)
    {
        super(message);
    }

    public ConnectionStatusAmqpUnauthorizedAcessException(String message, Throwable cause) { super(message, cause); }

    public ConnectionStatusAmqpUnauthorizedAcessException(Throwable cause) { super(cause); }
}
