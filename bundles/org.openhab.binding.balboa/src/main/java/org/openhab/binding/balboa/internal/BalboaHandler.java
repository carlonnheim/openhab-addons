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
 */
package org.openhab.binding.balboa.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.balboa.internal.BalboaMessage.PanelConfigurationResponseMessage;
import org.openhab.binding.balboa.internal.BalboaMessage.ToggleMessage.ToggleType;
import org.openhab.binding.balboa.internal.BalboaProtocol.Handler;
import org.openhab.binding.balboa.internal.BalboaProtocol.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BalboaHandler} is responsible for handling Balboa things.
 *
 * @author Carl Önnheim - Initial contribution
 */
@NonNullByDefault
public class BalboaHandler extends BaseThingHandler implements Handler {

    private final Logger logger = LoggerFactory.getLogger(BalboaHandler.class);

    private BalboaConfiguration config = getConfigAs(BalboaConfiguration.class);
    private BalboaProtocol protocol = new BalboaProtocol(this);
    private Runnable reconnect;
    private boolean reconnectable;
    private HashMap<ChannelUID, BalboaChannel> channels = new HashMap<ChannelUID, BalboaChannel>();

    public BalboaHandler(Thing thing) {
        super(thing);

        // Reconnect runnable.
        BalboaHandler bh = this;
        reconnect = new Runnable() {
            @Override
            public void run() {
                logger.debug("Reconnecting...");
                bh.connect();
            }
        };
        reconnectable = false;
    }

    @Override
    public void initialize() {

        // Initialize the status as UNKNOWN. The protocol will update the status in callbacks.
        updateStatus(ThingStatus.UNKNOWN);

        // Reconnect attempts are allowed
        reconnectable = true;

        // Initiate a connection
        connect();
    }

    // Attempts to connect the protocol
    private void connect() {
        config = getConfigAs(BalboaConfiguration.class);
        logger.debug("Starting balboa protocol with {} at {}", config.host, config.port);
        protocol.connect(config.host, config.port);
    }

    @Override
    public void dispose() {
        // Disallow reconnect attempts and disconnect
        reconnectable = false;
        protocol.disconnect();
    }

    /**
     * Handles state changes on the protocol.
     */
    @Override
    public void onStateChange(Status status, String detail) {
        switch (status) {
            case INITIAL:
                break;
            case CONFIGURATION_PENDING:
                logger.debug("Balboa Protocol Pending Ponfiguration: {}", detail);
                updateStatus(ThingStatus.ONLINE, ThingStatusDetail.ONLINE.CONFIGURATION_PENDING, detail);
                break;
            case ERROR:
                logger.debug("Balboa Protocol Error: {}", detail);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, detail);
                // Reconnect if this was not intentional
                if (reconnectable) {
                    scheduler.schedule(reconnect, config.reconnectInterval, TimeUnit.SECONDS);
                    logger.debug("Reconnection attempt in {} seconds", config.reconnectInterval);
                }
                break;
            case OFFLINE:
                // Reconnect if this was not intentional
                if (reconnectable) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, detail);
                    config = getConfigAs(BalboaConfiguration.class);
                    scheduler.schedule(reconnect, config.reconnectInterval, TimeUnit.SECONDS);
                    logger.debug("Reconnection attempt in {} seconds", config.reconnectInterval);
                } else {
                    logger.debug("Disconnected after disposal, no action taken");
                }
                break;
            case ONLINE:
                updateStatus(ThingStatus.ONLINE);
                logger.debug("Balboa Protocol is Online");
                break;
            default:
                break;
        }

    }

    @Override
    public void onMessage(BalboaMessage message) {
        logger.debug("Received a {}", message.getClass().getName());

        // Update the thing configuration (channels) on panel configuration messages
        if (message instanceof BalboaMessage.PanelConfigurationResponseMessage) {
            BalboaMessage.PanelConfigurationResponseMessage config = (PanelConfigurationResponseMessage) message;
            // Clear any channels we have from before - start over
            channels.clear();

            // Local variable to instantiate channels
            BalboaChannel channel;

            // Add pumps
            for (int i = 0; i < BalboaProtocol.MAX_PUMPS; i++) {
                switch (config.getPump(i)) {
                    // One-speed pump
                    case 0x01:
                        channel = new OneSpeedToggle(ToggleType.PUMP, i, String.format("pump-%d", i + 1),
                                String.format("Jet Pump %d, one-speed", i + 1), Arrays.asList("Switchable"));
                        channels.put(channel.getChannelUID(), channel);
                        break;
                    // Two-speed pump
                    case 0x02:
                        channel = new TwoSpeedToggle(ToggleType.PUMP, i, String.format("pump-%d", i + 1),
                                String.format("Jet Pump %d, two-speed", i + 1), null);
                        channels.put(channel.getChannelUID(), channel);
                        break;
                }
            }

            // Add ligths
            for (int i = 0; i < BalboaProtocol.MAX_LIGHTS; i++) {
                switch (config.getLight(i)) {
                    // One-level light
                    case 0x01:
                        channel = new OneSpeedToggle(ToggleType.LIGHT, i, String.format("light-%d", i + 1),
                                String.format("Light %d, one-level", i + 1), Arrays.asList("Switchable"));
                        channels.put(channel.getChannelUID(), channel);
                        break;
                    // Two-level light
                    case 0x02:
                        channel = new TwoSpeedToggle(ToggleType.LIGHT, i, String.format("light-%d", i + 1),
                                String.format("Light %d, two-level", i + 1), null);
                        channels.put(channel.getChannelUID(), channel);
                        break;
                }
            }

            // Add aux
            for (int i = 0; i < BalboaProtocol.MAX_AUX; i++) {
                if (config.getAux(i)) {
                    channel = new OneSpeedToggle(ToggleType.AUX, i, String.format("aux-%d", i + 1),
                            String.format("AUX %d, one-speed", i + 1), Arrays.asList("Switchable"));
                    channels.put(channel.getChannelUID(), channel);
                }
            }

            // Blower
            switch (config.getBlower()) {
                // One-speed blower
                case 0x01:
                    channel = new OneSpeedToggle(ToggleType.BLOWER, 0, "blower", "Blower, one-speed",
                            Arrays.asList("Switchable"));
                    channels.put(channel.getChannelUID(), channel);
                    break;
                // Two-speed blower
                case 0x02:
                    channel = new TwoSpeedToggle(ToggleType.BLOWER, 0, "blower", "Blower, two-speed", null);
                    channels.put(channel.getChannelUID(), channel);
                    break;
            }

            // Mister
            switch (config.getMister()) {
                // One-speed mister
                case 0x01:
                    channel = new OneSpeedToggle(ToggleType.MISTER, 0, "mister", "Mister, one-speed",
                            Arrays.asList("Switchable"));
                    channels.put(channel.getChannelUID(), channel);
                    break;
                // Two-speed mister
                case 0x02:
                    channel = new TwoSpeedToggle(ToggleType.MISTER, 0, "mister", "Mister, two-speed", null);
                    channels.put(channel.getChannelUID(), channel);
                    break;
            }

            // Build the channels structure on the thing. Start by wiping all channels
            ThingBuilder builder = editThing().withoutChannels(getThing().getChannels());
            // Add the new channels
            for (BalboaChannel c : channels.values()) {
                builder.withChannel(c.getChannel());
            }

            // Update the thing with the new channels
            updateThing(builder.build());
        } else {
            // Any other message type is passed to the respective channels to determine state updates
            for (BalboaChannel c : channels.values()) {
                c.handleUpdate(message);
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Pass the command to the given channel
        if (channels.containsKey(channelUID)) {
            channels.get(channelUID).handleCommand(command);
        } else {
            logger.warn("Command received on unknown channel: {}", channelUID.getAsString());
        }
    }

    // Needed to keep track of state between message types
    private boolean celcius, time24h;

    /**
     * Channels exposed by the Balboa Unit are handled by classes implementing the {@link BalboaChannel} interface.
     *
     * @author CarlÖnnheim
     *
     */
    private interface BalboaChannel {
        /**
         * Returns the {@link ChannelUID} of a {@link BalboaChannel}
         *
         * @return A {@link ChannelUID}
         */
        public ChannelUID getChannelUID();

        /**
         * Returns the {@link Channel} representation of a {@link BalboaChannel}
         *
         * @return A {@link Channel}
         */
        public Channel getChannel();

        /**
         * Handles commands sent to the channel
         *
         * @param command
         */
        public void handleCommand(Command command);

        /**
         * Transforms incoming messages from the Balboa Unit to status updates of the {@link Thing}
         *
         * @param message
         */
        public void handleUpdate(BalboaMessage message);
    }

    /**
     * Handles One-Speed toggle items
     *
     * @author CarlÖnnheim
     *
     */
    private class OneSpeedToggle implements BalboaChannel {
        private int index;
        private ToggleType type;
        private ChannelUID channelUID;
        private String description;
        private List<String> tags;
        private OnOffType state = OnOffType.OFF;

        /**
         * Instantiate a one-speed toggle item.
         *
         * @param index
         */
        protected OneSpeedToggle(ToggleType type, int index, String id, String description, List<String> tags) {
            this.type = type;
            this.index = index;
            this.channelUID = new ChannelUID(thing.getUID(), id);
            this.description = description;
            this.tags = tags;
            // Sanity check the index
            if (this.index < 0 || this.index > this.type.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }
        }

        /**
         * Return the Channel UID.
         */
        @Override
        public ChannelUID getChannelUID() {
            return channelUID;
        }

        /**
         * Build a channel representing the one-speed toggle item.
         */
        @Override
        public Channel getChannel() {
            ChannelBuilder builder = ChannelBuilder.create(channelUID, "Switch").withLabel(description)
                    .withDescription(description).withDefaultTags(new HashSet<>(tags));
            return builder.build();
        }

        /**
         * Set the item to the desired state (ON/OFF)
         */
        @Override
        public void handleCommand(Command command) {
            // Send a toggle if the desired state is not equal to the current state
            if (command instanceof OnOffType) {
                if (command != state) {
                    protocol.sendMessage(new BalboaMessage.ToggleMessage(type, index));
                }
            } else if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("One-speed channel received update of type {}", command.getClass().getSimpleName());
            }

        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state of the item and determine the OH state.
                byte rawState = ((BalboaMessage.StatusUpdateMessage) message).getToggleItem(type, index);
                state = rawState == 0 ? OnOffType.OFF : OnOffType.ON;
                // Make the update
                updateState(channelUID, state);
            }
        }

    }

    /**
     * Handles Two-Speed toggle items
     *
     * @author CarlÖnnheim
     *
     */
    private class TwoSpeedToggle implements BalboaChannel {
        private int index;
        private ToggleType type;
        private ChannelUID channelUID;
        private String description;
        private @Nullable List<String> tags;
        private StringType state = StringType.EMPTY;
        private byte rawState;

        /**
         * Instantiate a two-speed toggle item.
         *
         * @param index
         */
        protected TwoSpeedToggle(ToggleType type, int index, String id, String description,
                @Nullable List<String> tags) {
            this.type = type;
            this.index = index;
            this.channelUID = new ChannelUID(thing.getUID(), id);
            this.description = description;
            this.tags = tags;
            // Sanity check the index
            if (this.index < 0 || this.index > this.type.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }
        }

        /**
         * Return the Channel UID.
         */
        @Override
        public ChannelUID getChannelUID() {
            return channelUID;
        }

        /**
         * Build a channel representing the two-speed toggle item.
         */
        @Override
        public Channel getChannel() {
            ChannelBuilder builder = ChannelBuilder.create(channelUID, "String").withLabel(description)
                    .withDescription(description);
            if (tags != null) {
                builder.withDefaultTags(new HashSet<>(tags));
            }
            // TODO: Add State Descriptions
            return builder.build();
        }

        /**
         * Set the item to the desired state (ON/OFF)
         */
        @Override
        public void handleCommand(Command command) {
            // Send a toggle if the desired state is not equal to the current state
            if (command instanceof StringType) {
                /*
                 * TODO: implement this
                 * if (command != state) {
                 * protocol.sendMessage(new BalboaMessage.ToggleMessage(type, index));
                 * }
                 */
            } else if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("Two-speed channel received update of type {}", command.getClass().getSimpleName());
            }

        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state of the item and determine the OH state.
                rawState = ((BalboaMessage.StatusUpdateMessage) message).getToggleItem(type, index);
                switch (rawState) {
                    case 0x00:
                        state = StringType.valueOf("OFF");
                        break;
                    case 0x01:
                        state = StringType.valueOf("LOW");
                        break;
                    case 0x02:
                        state = StringType.valueOf("HIGH");
                        break;
                    default:
                        state = StringType.EMPTY;
                        break;
                }
                // Make the update
                updateState(channelUID, state);
            }
        }

    }

}
