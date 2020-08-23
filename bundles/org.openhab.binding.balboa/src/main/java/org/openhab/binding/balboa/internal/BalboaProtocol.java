/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Information on the Balboa protocol itself taken from https://github.com/ccutrer/balboa_worldwide_app
 *
 */
package org.openhab.binding.balboa.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.LinkedList;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.balboa.internal.BalboaMessage.PanelConfigurationResponseMessage;
import org.openhab.binding.balboa.internal.BalboaMessage.SettingsRequestMessage.SettingsType;
import org.openhab.binding.balboa.internal.BalboaMessage.StatusUpdateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BalboaProtocol} implements the communication protocol with Balboa control units.
 *
 * @author Carl Önnheim - Initial contribution
 */
@NonNullByDefault
public class BalboaProtocol {

    private final Logger logger = LoggerFactory.getLogger(BalboaProtocol.class);

    // Constants
    private static final int BUF_SIZE = 512;
    private static final int DEFAULT_PORT = 4257;
    public static final int MAX_PUMPS = 6;
    public static final int MAX_LIGHTS = 2;
    public static final int MAX_AUX = 2;

    // Variables
    private @Nullable AsynchronousSocketChannel socket = null;
    private BalboaProtocol.Handler handler;
    private Writer writer = new Writer();
    private Reader reader = new Reader();
    private boolean babble = false;
    private Status status = Status.INITIAL;

    /**
     * Constructor for {@link BalboaProtocol}
     *
     */
    public BalboaProtocol(BalboaProtocol.Handler handler) {
        super();
        this.handler = handler;
    }

    // Start and end separator on each message
    private static final byte MESSAGE_SEPARATOR = 0x7e;
    // Parameters for the CRC check
    private static byte[] crcTable;
    private static final byte INIT = 0x02;
    private static final byte POLY = 0x07;
    private static final byte FINAL_XOR = 0x02;

    /**
     * Enumerates the statuses the protocol can be in. Possible transitions are
     * INITIAL: ERROR, CONNECTING
     * CONNECTING: ERROR, OFFLINE, CONFIGURATION_PENDING
     * CONFIGURATION_PENDING: ONLINE, OFFLINE
     * ONLINE: OFFLINE
     * OFFLINE: ERROR, CONNECTING
     * ERROR: CONNECTING
     *
     * @author CarlÖnnheim
     *
     */
    public enum Status {
        INITIAL,
        CONNECTING,
        OFFLINE,
        ERROR,
        CONFIGURATION_PENDING,
        ONLINE
    }

    /**
     * Callback interface for Handlers. The handlers receive callbacks when the protocol state changes and when messages
     * are received
     *
     * @author CarlÖnnheim
     *
     */
    public interface Handler {
        // Callback for when the protocol transitions between states
        public void onStateChange(Status status, String detail);

        // Callback for when the protocol receives a message
        public void onMessage(BalboaMessage message);
    }

    /**
     * Internal handler when a message is received. Calls the external handler when done with internal processing
     *
     * @param message
     */
    private void onMessage(BalboaMessage message) {
        // Pass the message to the caller (this configures channels if it is a PanelConfiguration)
        handler.onMessage(message);

        // Handle internal state updates after that
        switch (message.getMessageType()) {
            case StatusUpdateMessage.MESSAGE_TYPE:
                break;
            case PanelConfigurationResponseMessage.MESSAGE_TYPE:
                setStatus(Status.ONLINE, "Configured with pumps, lights, ...");
                break;
            default:
                break;
        }

    }

    /**
     * Returns if this {@link BalboaProtocol} will babble or not
     *
     * Balboa units provide status updates several times per second. All of them will be returned if the protocol is set
     * to babble. Sequential messages with the same CRC value will be discarded otherwise. Since the CRC includes the
     * message type and status updates include the clock to minute accuracy, it is in practice safe to discard these.
     *
     */
    public boolean babble() {
        return babble;
    }

    /**
     * Sets the {@link BalboaProtocol} to babble or not
     *
     * See the setter description for details.
     *
     */
    public void babble(boolean babble) {
        this.babble = babble;
    }

    // Initialize the crc lookup table
    static {
        crcTable = new byte[256];
        // Produce the result for all possible bytes
        for (int dividend = 0; dividend < 256; dividend++) {
            byte currByte = (byte) dividend;
            // Process each bit
            for (byte bit = 0; bit < 8; bit++) {
                // If the MSB is set, shift and XOR
                if ((currByte & 0x80) != 0) {
                    currByte <<= 1;
                    currByte ^= POLY;
                } else {
                    // Otherwise, just shift
                    currByte <<= 1;
                }
            }
            // Store the result
            crcTable[dividend] = currByte;
        }

    }

    // CRC calculation routine
    private static byte calculateCrc(byte[] buffer) {
        // Sanity check
        if (buffer.length < 1) {
            throw new IllegalArgumentException("Tried to calculate CRC for a buffer with no data");
        }

        // Calculate the CRC
        byte crc = INIT;
        // Start from the length byte (start + 1), exclude the last two bytes
        for (int i = 1; i < buffer.length - 2; i++) {
            crc = crcTable[(buffer[i] ^ crc) & 0xFF];
        }
        // Final xor and return
        return (byte) (crc ^ FINAL_XOR);
    }

    /**
     * Sends a {@link BalboaMessage.Outbound}
     *
     * @param message the outbound message to send
     */
    public void sendMessage(BalboaMessage.Outbound message) {
        // Delegate to the writer instance
        writer.sendMessage(message);
    }

    private class Writer implements CompletionHandler<Integer, ByteBuffer> {

        private LinkedList<ByteBuffer> queue = new LinkedList<ByteBuffer>();;
        private boolean writeInProgress = false;

        public Writer() {
        }

        /**
         * Resets a {@link BalboaProtocol.Writer}, should be called between connects
         *
         */
        synchronized public void reset() {
            queue.clear();
        }

        public void sendMessage(BalboaMessage.Outbound message) {

            // Get the payload
            byte[] payload = message.getPayload();

            // Length excluding first separator and the length byte itself
            byte messageLength = (byte) (payload.length + 5);

            // Allocate a buffer for the message including separators
            byte[] buffer = new byte[messageLength + 2];

            // Message start
            buffer[0] = MESSAGE_SEPARATOR;
            buffer[1] = messageLength;
            // Message type
            int mt = message.getMessageType();
            buffer[2] = (byte) ((mt >> 16) & 0xFF);
            buffer[3] = (byte) ((mt >> 8) & 0xFF);
            buffer[4] = (byte) (mt & 0xFF);
            // The payload
            for (int i = 0; i < payload.length; i++) {
                buffer[i + 5] = payload[i];
            }
            buffer[messageLength] = calculateCrc(buffer);
            // Message end
            buffer[messageLength + 1] = MESSAGE_SEPARATOR;

            // Wrap the data in a ByteBuffer and start writing it
            logger.trace("Writing {} bytes: {}", buffer.length, DatatypeConverter.printHexBinary(buffer));
            startWrite(ByteBuffer.wrap(buffer));
        }

        synchronized private void startWrite(ByteBuffer buffer) {
            // We must be connected
            if (socket == null) {
                throw new IllegalStateException("Cannot send message while not connected");
            }

            // Queue the item if writing is already in progress
            if (writeInProgress) {
                queue.add(buffer);
            } else {
                // Otherwise start writing
                logger.trace("Write session started");
                writeInProgress = true;
                socket.write(buffer, buffer, this);
            }
        }

        @Override
        synchronized public void completed(Integer result, ByteBuffer buffer) {
            // Check if the message was written in full, otherwise resume the write
            if (buffer.position() < buffer.limit()) {
                logger.trace("Partial write, resuming");
                socket.write(buffer, buffer, this);
                return;
            }

            // Check if there is anything queued
            if (queue.isEmpty()) {
                // If not, we are done with this write session
                logger.trace("Write session ended");
                writeInProgress = false;
            } else {
                // Otherwise, trigger the next write
                logger.trace("Message queued, write session continued");
                ByteBuffer b = queue.remove();
                socket.write(b, b, this);
            }
        }

        @Override
        synchronized public void failed(@Nullable Throwable exc, ByteBuffer buffer) {
            // Something failed. Disconnect the protocol
            writeInProgress = false;
            disconnect();
        }
    }

    private class Reader implements CompletionHandler<Integer, @NonNull BalboaProtocol.Reader> {

        private ByteBuffer readBuffer = ByteBuffer.allocate(BUF_SIZE);
        private byte lastCRC = 0;

        /**
         * Resets a {@link BalboaProtocol.Reader}, should be called between connects
         *
         */
        synchronized public void reset() {
            readBuffer.clear();
        }

        /**
         * Decodes {@link BalboaMessage} objects from a received buffer.
         *
         * @param data new data to decode
         * @param dataLength number of valid bytes in the data
         *
         */
        @Override
        public void completed(Integer result, BalboaProtocol.Reader me) {
            // Check if we are disconnected
            if (result < 0) {
                disconnect();
            }

            // Limit the buffer at the end of the read and rewind to the start
            readBuffer.limit(readBuffer.position());
            readBuffer.rewind();

            logger.trace("{} bytes from Balboa Unit, buffer has {} bytes: {}", result, readBuffer.remaining(),
                    DatatypeConverter.printHexBinary(readBuffer.array()).substring(0, 2 * readBuffer.remaining()));

            // Decode messages (there may be more than one)
            while (readBuffer.hasRemaining()) {
                // Mark the position, if we do not get a full message we need to go back to this position
                readBuffer.mark();

                // The first byte must be a separator
                byte startByte = readBuffer.get();
                if (startByte != MESSAGE_SEPARATOR) {
                    logger.debug("Message did not start with {}, got {}", MESSAGE_SEPARATOR, startByte);
                    // Discard the whole buffer in this case
                    readBuffer.position(0);
                    readBuffer.limit(0);
                    break;
                }

                // Second byte is the length byte
                int messageLength = readBuffer.get();

                // Make sure the full message is in the buffer
                if (messageLength > readBuffer.remaining()) {
                    logger.trace("Incomplete message, waiting for more data");
                    // Return to the start mark and stop processing
                    readBuffer.reset();
                    break;
                }

                // Suppress the babble by comparing CRC values, which is the second last position of the new message.
                if (readBuffer.get(readBuffer.position() + messageLength - 2) == lastCRC && !babble
                        && status == Status.ONLINE) {
                    logger.trace("Suppressed repeated message");
                    readBuffer.position(readBuffer.position() + messageLength);
                    continue;
                }

                // Extract the full message
                byte[] message = new byte[messageLength + 2];
                message[0] = startByte;
                message[1] = (byte) messageLength;
                readBuffer.get(message, 2, messageLength);
                logger.trace("Processing message: {}", DatatypeConverter.printHexBinary(message));

                // Check that there is a separator at the end.
                if (message[message.length - 1] != MESSAGE_SEPARATOR) {
                    logger.debug("Message did not end with {}", MESSAGE_SEPARATOR);
                    // Discard the whole buffer in this case
                    readBuffer.position(0);
                    readBuffer.limit(0);
                    break;
                }

                // Length must be at least 5 (3 bytes message type, crc and separator)
                if (messageLength < 5) {
                    logger.debug("Message without message code received");
                    // The individual message is corrupt, but the buffer is sane - proceed to the next message
                    continue;
                }

                // Calculate the checksum and validate it.
                byte crc = calculateCrc(message);
                if (crc != message[message.length - 2]) {
                    logger.debug("CRC Error: calculated {}, received {} for buffer {}", crc,
                            message[message.length - 2], DatatypeConverter.printHexBinary(message));
                    // The individual message is corrupt, but the buffer is sane - proceed to the next message
                    continue;
                }

                // Remember the crc value for the next run (used to detect babble)
                lastCRC = crc;

                // Instantiate the message and callback if successful
                BalboaMessage bMsg = BalboaMessage.fromBuffer(message);
                if (bMsg != null) {
                    onMessage(bMsg);
                }
            }

            // Prepare the buffer to receive more data
            if (readBuffer.position() < readBuffer.limit()
                    && (readBuffer.limit() < readBuffer.capacity() || readBuffer.position() > 0)) {
                // There is unprocessed data on the buffer and room to fill up more
                readBuffer.compact();
                readBuffer.limit(readBuffer.capacity());
            } else {
                // Otherwise start from scratch
                readBuffer.clear();
            }

            // Start a new read (wait for more data)
            start();
        }

        /**
         * Starts a {@link BalboaProtocol.Reader}
         *
         */
        public void start() {
            if (socket != null) {
                socket.read(readBuffer, this, this);
            } else {
                logger.debug("Attempted to read while not connected");
                throw new IllegalStateException("Not connected");
            }
        }

        @Override
        public void failed(@Nullable Throwable exc, BalboaProtocol.Reader me) {
            // Something failed. Disconnect the protocol
            disconnect();
        }
    }

    private void setStatus(Status status, String detail) {
        this.status = status;
        handler.onStateChange(status, detail);
    }

    /**
     * Connects a {@link BalboaProtocol} on the default port (4257)
     *
     * @param host the hostname or ip address of the control unit.
     * @throws IOException
     */
    public void connect(String host) throws IOException {
        // Connect to the default port
        connect(host, DEFAULT_PORT);
    }

    /**
     * Connects a {@link BalboaProtocol}
     *
     * @param host the hostname or ip address of the control unit.
     * @param port the port to connect to.
     */
    // TODO: The completion handlers show warnings without this, should be a better way to fix
    @SuppressWarnings("null")
    public synchronized void connect(String host, int port) {
        // byte[] bbf = encode(new balboaMessage.SettingsRequestMessage(SettingsType.INFORMATION));
        // logger.trace("{} bytes to Balboa Unit: {}", bbf.length, DatatypeConverter.printHexBinary(bbf));

        // Do nothing if a connection is already in progress
        if (status == Status.CONNECTING) {
            return;
        }

        // Update status
        setStatus(Status.CONNECTING, "Connecting");

        // Disconnect if we are already connected
        if (socket != null) {
            disconnect();
        }

        // Resolve the host address
        InetSocketAddress hostAddress = null;
        try {
            hostAddress = new InetSocketAddress(host, port);
        } catch (Exception e) {
            setStatus(Status.ERROR, e.getMessage());
            return;
        }

        // Check that the resolution was successful
        if (hostAddress.isUnresolved()) {
            logger.debug("Failed to resolve host: {}", host);
            handler.onStateChange(Status.ERROR, String.format("Failed to resolve host: %s", host));
            return;
        }

        // Connect to the control unit
        try {
            socket = AsynchronousSocketChannel.open();
        } catch (IOException e) {
            logger.debug("Socket creation failed: {}", e.getMessage());
            handler.onStateChange(Status.ERROR, e.getMessage());
            return;
        }
        if (socket != null) {
            // Try to connect
            logger.debug("Balboa protocol connecting");
            socket.connect(hostAddress, this, new CompletionHandler<@NonNull Void, @NonNull BalboaProtocol>() {
                @Override
                public void completed(Void result, BalboaProtocol bp) {
                    // We are connected, but not configured yet. Request the information and update status
                    writer.sendMessage(new BalboaMessage.SettingsRequestMessage(SettingsType.INFORMATION));
                    writer.sendMessage(new BalboaMessage.SettingsRequestMessage(SettingsType.PANEL));
                    setStatus(Status.CONFIGURATION_PENDING, "Configuration request sent");

                    // Start the reader
                    reader.start();

                    // socket.write(ByteBuffer.wrap(DatatypeConverter.parseHexBinary("7E080ABF22020000897E")), bp,
                }

                @Override
                public void failed(@Nullable Throwable exc, BalboaProtocol bp) {
                    // Failed to connect, report the error
                    logger.debug("Connection Failed: {}", exc.getMessage());
                    handler.onStateChange(Status.ERROR, String.format("Connection Failed: %s", exc.getMessage()));
                }
            });
        }
    }

    /**
     * Disconnects a {@link BalboaProtocol}
     *
     */
    public synchronized void disconnect() {

        if (socket != null) {
            // Close the socket. Not much to do with any errors here.
            logger.debug("Balboa protocol disconnecting");
            try {
                socket.close();
            } catch (IOException e) {
                logger.warn("Failed to close the connection to the Balboa Control Unit: {}", e.getMessage());
            }
        } else {
            logger.debug("Not connected when disconnect attempted.");
        }

        // The socket is no longer valid, i.e. we are disconnected.
        socket = null;

        // Reset the reader and writer
        reader.reset();
        writer.reset();

        setStatus(Status.OFFLINE, "Disconnected");
    }

}
