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

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.unit.ImperialUnits;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.balboa.internal.BalboaMessage.ItemType;
import org.openhab.binding.balboa.internal.BalboaMessage.PanelConfigurationResponseMessage;
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

            // These channels are always there
            channel = new TemperatureChannel("current-temperature", "Current Temperature", false);
            channels.put(channel.getChannelUID(), channel);

            channel = new TemperatureChannel("target-temperature", "Target Temperature", true);
            channels.put(channel.getChannelUID(), channel);

            channel = new TemperatureScale();
            channels.put(channel.getChannelUID(), channel);

            channel = new TemperatureRange();
            channels.put(channel.getChannelUID(), channel);

            channel = new HeatMode();
            channels.put(channel.getChannelUID(), channel);

            channel = new FilterStatus();
            channels.put(channel.getChannelUID(), channel);

            channel = new Contact(ItemType.PRIMING, "priming", "Priming", "priming");
            channels.put(channel.getChannelUID(), channel);

            channel = new Contact(ItemType.CIRCULATION, "circulation", "Circulation Pump", "circulation");
            channels.put(channel.getChannelUID(), channel);

            channel = new Contact(ItemType.HEATER, "heater", "Heater", "heater");
            channels.put(channel.getChannelUID(), channel);

            // Add pumps
            for (int i = 0; i < BalboaProtocol.MAX_PUMPS; i++) {
                switch (config.getPump(i)) {
                    // One-speed pump
                    case 0x01:
                        channel = new OneSpeedToggle(ItemType.PUMP, i, String.format("pump-%d", i + 1),
                                String.format("Jet Pump %d, one-speed", i + 1), "pump1");
                        channels.put(channel.getChannelUID(), channel);
                        break;
                    // Two-speed pump
                    case 0x02:
                        channel = new TwoSpeedToggle(ItemType.PUMP, i, String.format("pump-%d", i + 1),
                                String.format("Jet Pump %d, two-speed", i + 1), "pump2");
                        channels.put(channel.getChannelUID(), channel);
                        break;
                }
            }

            // Add ligths
            for (int i = 0; i < BalboaProtocol.MAX_LIGHTS; i++) {
                switch (config.getLight(i)) {
                    // One-level light
                    case 0x01:
                        channel = new OneSpeedToggle(ItemType.LIGHT, i, String.format("light-%d", i + 1),
                                String.format("Light %d, one-level", i + 1), "light1");
                        channels.put(channel.getChannelUID(), channel);
                        break;
                    // Two-level light
                    case 0x02:
                        channel = new TwoSpeedToggle(ItemType.LIGHT, i, String.format("light-%d", i + 1),
                                String.format("Light %d, two-level", i + 1), "light2");
                        channels.put(channel.getChannelUID(), channel);
                        break;
                }
            }

            // Add aux
            for (int i = 0; i < BalboaProtocol.MAX_AUX; i++) {
                if (config.getAux(i)) {
                    channel = new OneSpeedToggle(ItemType.AUX, i, String.format("aux-%d", i + 1),
                            String.format("AUX %d, one-speed", i + 1), "aux");
                    channels.put(channel.getChannelUID(), channel);
                }
            }

            // Blower
            switch (config.getBlower()) {
                // One-speed blower
                case 0x01:
                    channel = new OneSpeedToggle(ItemType.BLOWER, 0, "blower", "Blower, one-speed", "blower1");
                    channels.put(channel.getChannelUID(), channel);
                    break;
                // Two-speed blower
                case 0x02:
                    channel = new TwoSpeedToggle(ItemType.BLOWER, 0, "blower", "Blower, two-speed", "blower2");
                    channels.put(channel.getChannelUID(), channel);
                    break;
            }

            // Mister
            switch (config.getMister()) {
                // One-speed mister
                case 0x01:
                    channel = new OneSpeedToggle(ItemType.MISTER, 0, "mister1", "Mister, one-speed", "mister1");
                    channels.put(channel.getChannelUID(), channel);
                    break;
                // Two-speed mister
                case 0x02:
                    channel = new TwoSpeedToggle(ItemType.MISTER, 0, "mister2", "Mister, two-speed", "mister2");
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
    private boolean celcius, time24h, temperatureHighRange;

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
     * Base class for channels, implementing common logic
     *
     * @author CarlÖnnheim
     *
     */
    private abstract class BaseBalboaChannel implements BalboaChannel {
        private ChannelUID channelUID;
        private String description;
        private String channelType;
        private String itemType;

        /**
         * Constructs the enumeration channel from a is, description and channel type.
         *
         * @param id
         * @param description
         * @param channelType
         */
        protected BaseBalboaChannel(String id, String description, String channelType, String itemType) {
            this.channelUID = new ChannelUID(thing.getUID(), id);
            this.description = description;
            this.channelType = channelType;
            this.itemType = itemType;
        }

        /**
         * Return the Channel UID.
         */
        @Override
        public ChannelUID getChannelUID() {
            return channelUID;
        }

        /**
         * Build a channel.
         */
        @Override
        public Channel getChannel() {
            new ChannelTypeUID(BalboaBindingConstants.BINDING_ID, channelType);
            ChannelBuilder builder = ChannelBuilder.create(channelUID, itemType).withLabel(description)
                    .withDescription(description)
                    .withType(new ChannelTypeUID(BalboaBindingConstants.BINDING_ID, channelType));
            return builder.build();
        }

    }

    /**
     * Handles contact items
     *
     * @author Carl Önnheim
     *
     */
    private class Contact extends BaseBalboaChannel {
        private ItemType type;
        private OpenClosedType state = OpenClosedType.CLOSED;

        /**
         * Instantiate a contact item.
         *
         */
        protected Contact(ItemType type, String id, String description, String channelType) {
            super(id, description, channelType, "Contact");
            this.type = type;
        }

        /**
         * Updates will have no effect
         *
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("Contact channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         *
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state of the item and determine the OH state.
                byte rawState = ((BalboaMessage.StatusUpdateMessage) message).getItem(type, 0);
                state = rawState == 0 ? OpenClosedType.CLOSED : OpenClosedType.OPEN;
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Handles One-Speed toggle items
     *
     * @author CarlÖnnheim
     *
     */
    private class OneSpeedToggle extends BaseBalboaChannel {
        private int index;
        private ItemType type;
        private OnOffType state = OnOffType.OFF;

        /**
         * Instantiate a one-speed toggle item.
         *
         * @param index
         */
        protected OneSpeedToggle(ItemType type, int index, String id, String description, String channelType) {
            super(id, description, channelType, "Switch");
            this.type = type;
            this.index = index;
            // Sanity check the index
            if (this.index < 0 || this.index >= this.type.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }
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
                byte rawState = ((BalboaMessage.StatusUpdateMessage) message).getItem(type, index);
                state = rawState == 0 ? OnOffType.OFF : OnOffType.ON;
                // Make the update
                updateState(getChannelUID(), state);
            }
        }

    }

    /**
     * Base class for channels using enumerated code values
     *
     * @author CarlÖnnheim
     *
     */
    private abstract class EnumerationChannel extends BaseBalboaChannel {
        protected StringType state = StringType.EMPTY;

        /**
         * Constructs the enumeration channel from a id, description and channel type.
         *
         * @param id
         * @param description
         * @param channelType
         */
        protected EnumerationChannel(String id, String description, String channelType) {
            super(id, description, channelType, "String");
        }

    }

    /**
     * Handles Two-Speed toggle items
     *
     * @author CarlÖnnheim
     *
     */
    private class TwoSpeedToggle extends EnumerationChannel {
        private int index;
        private ItemType type;
        private StringType state = StringType.EMPTY;
        private byte rawState;

        /**
         * Instantiate a two-speed toggle item.
         *
         * @param index
         */
        protected TwoSpeedToggle(ItemType type, int index, String id, String description, String channelType) {
            super(id, description, channelType);
            this.type = type;
            this.index = index;
            // Sanity check the index
            if (this.index < 0 || this.index >= this.type.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof StringType) {
                // Determine how many times to switch (state wraps around 0 -> 1 -> 2 -> 0)
                int count = 0;
                switch (command.toString()) {
                    case "OFF":
                        count = (0 - rawState) % 3;
                        break;
                    case "LOW":
                        count = (1 - rawState) % 3;
                        break;
                    case "HIGH":
                        count = (2 - rawState) % 3;
                        break;
                    default:
                        // Unknown target state, do nothing
                        count = 0;
                        break;
                }
                // Toggle that many times
                for (int i = 0; i < count; i++) {
                    protocol.sendMessage(new BalboaMessage.ToggleMessage(type, index));
                }
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
                rawState = ((BalboaMessage.StatusUpdateMessage) message).getItem(type, index);
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
                updateState(getChannelUID(), state);
            }
        }

    }

    /**
     * Handles the Heat Mode
     *
     * @author Carl Önnheim
     *
     */
    private class HeatMode extends EnumerationChannel {
        private byte rawState;

        /**
         * Instantiate a heat mode item.
         *
         * @param index
         */
        protected HeatMode() {
            super("heat-mode", "Heat Mode", "heat-mode");
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            // Send a toggle if the desired state is not equal to the current state
            if (command instanceof StringType) {
                // Switch based on where the user wants to go
                switch (command.toString()) {
                    case "READY":
                        // We need to toggle if the first bit is set
                        if ((rawState & 0x01) != 0) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.HEAT_MODE, 0));
                        }
                        break;
                    case "REST":
                    case "READY_IN_REST":
                        // We need to toggle if the first bit is not set
                        if ((rawState & 0x01) == 0) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.HEAT_MODE, 0));
                        }
                        break;
                }
            } else if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("Heat Mode channel received update of type {}", command.getClass().getSimpleName());
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
                rawState = ((BalboaMessage.StatusUpdateMessage) message).getReadyState();
                switch (rawState) {
                    case 0x00:
                        state = StringType.valueOf("READY");
                        break;
                    case 0x01:
                        state = StringType.valueOf("REST");
                        break;
                    case 0x03:
                        state = StringType.valueOf("READY_IN_REST");
                        break;
                    default:
                        state = StringType.EMPTY;
                        break;
                }
                // Make the update
                updateState(getChannelUID(), state);
            }
        }

    }

    /**
     * Handles the Temperature Scale
     *
     * @author Carl Önnheim
     *
     */
    private class TemperatureScale extends EnumerationChannel {

        /**
         * Instantiate a temperature scale item.
         *
         * @param index
         */
        protected TemperatureScale() {
            super("temperature-scale", "Temperature Scale", "temperature-scale");
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            // Set the desired temperature scale
            if (command instanceof StringType) {
                switch (command.toString()) {
                    case "C":
                        protocol.sendMessage(new BalboaMessage.SetTemperatureScaleMessage(true));
                        break;
                    case "F":
                        protocol.sendMessage(new BalboaMessage.SetTemperatureScaleMessage(false));
                        break;
                }
            } else if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("Temperature Scale channel received update of type {}", command.getClass().getSimpleName());
            }

        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Remember the state at handler level, since it is needed on other channels.
                celcius = ((BalboaMessage.StatusUpdateMessage) message).getCelciusDisplay();
                state = celcius ? StringType.valueOf("C") : StringType.valueOf("F");
                // Make the update
                updateState(getChannelUID(), state);
            }
        }

    }

    /**
     * Handles the Temperature Range
     *
     * @author Carl Önnheim
     *
     */
    private class TemperatureRange extends EnumerationChannel {

        /**
         * Instantiate a temperature range item.
         *
         * @param index
         */
        protected TemperatureRange() {
            super("temperature-range", "Temperature Range", "temperature-range");
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            // Set the desired temperature range
            if (command instanceof StringType) {
                // Toggle if we are not already at the desired state
                switch (command.toString()) {
                    case "LOW":
                        if (temperatureHighRange) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.TEMPERATURE_RANGE, 0));
                        }
                        break;
                    case "HIGH":
                        if (!temperatureHighRange) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.TEMPERATURE_RANGE, 0));
                        }
                        break;
                }
            } else if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("Temperature Range channel received update of type {}", command.getClass().getSimpleName());
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
                temperatureHighRange = ((BalboaMessage.StatusUpdateMessage) message).getItem(ItemType.TEMPERATURE_RANGE,
                        0) != 0x00;
                state = temperatureHighRange ? StringType.valueOf("HIGH") : StringType.valueOf("LOW");
                // Make the update
                updateState(getChannelUID(), state);
            }
        }

    }

    /**
     * Handles the Filter Status
     *
     * @author Carl Önnheim
     *
     */
    private class FilterStatus extends EnumerationChannel {

        /**
         * Instantiate a Filter status item.
         *
         */
        protected FilterStatus() {
            super("filter", "Filter Status", "filter");
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("Filter Status channel received update of type {}", command.getClass().getSimpleName());
            }

        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Determine state
                switch (((BalboaMessage.StatusUpdateMessage) message).getFilterState()) {
                    case 0x01:
                        state = StringType.valueOf("1");
                        break;
                    case 0x02:
                        state = StringType.valueOf("2");
                        break;
                    case 0x03:
                        state = StringType.valueOf("1+2");
                        break;
                    default:
                        state = StringType.valueOf("OFF");
                        break;
                }
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Handles the Temperatures
     *
     * @author Carl Önnheim
     *
     */
    private class TemperatureChannel extends BaseBalboaChannel {

        private boolean isTarget;

        /**
         * Instantiate a Temperature item.
         *
         */
        protected TemperatureChannel(String id, String description, boolean target) {
            super(id, description, target ? "target-temperature" : "current-temperature", "Number:Temperature");
            this.isTarget = target;
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof QuantityType<?> && isTarget) {
                QuantityType<?> target = (QuantityType<?>) command;
                double targetTemperature;
                if (celcius) {
                    targetTemperature = target.toUnit(SIUnits.CELSIUS).doubleValue();
                } else {
                    targetTemperature = target.toUnit(ImperialUnits.FAHRENHEIT).doubleValue();
                }
                protocol.sendMessage(
                        new BalboaMessage.SetTemperatureMessage(targetTemperature, celcius, temperatureHighRange));
            } else if (command instanceof RefreshType) {
                // No action is relevant, just pass
            } else {
                logger.warn("Filter Status channel received update of type {}", command.getClass().getSimpleName());
            }

        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state
                double rawState = ((BalboaMessage.StatusUpdateMessage) message).getTemperature(isTarget);
                // Set the proper unit of the value
                QuantityType<Temperature> state;
                if (((BalboaMessage.StatusUpdateMessage) message).getCelciusDisplay()) {
                    state = new QuantityType<Temperature>(rawState, SIUnits.CELSIUS);
                } else {
                    state = new QuantityType<Temperature>(rawState, ImperialUnits.FAHRENHEIT);
                }
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

}
