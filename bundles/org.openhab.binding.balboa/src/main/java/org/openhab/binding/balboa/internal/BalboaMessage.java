package org.openhab.binding.balboa.internal;

import java.util.HashMap;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BalboaMessage} represents the messages exchanged with a Balboa Control Unit.
 *
 * @author Carl Önnheim - Initial contribution
 */
@NonNullByDefault
public class BalboaMessage {

    private static final Logger logger = LoggerFactory.getLogger(BalboaMessage.class);

    public static final int MESSAGE_TYPE = 0;
    public static final int MESSAGE_LENGTH = 0;

    // Message type map
    private static HashMap<Integer, Class<? extends BalboaMessage>> messageTypeMap = new HashMap<Integer, Class<? extends BalboaMessage>>();

    // Inititalize message type map
    static {
        messageTypeMap.put(StatusUpdateMessage.MESSAGE_TYPE, StatusUpdateMessage.class);
        messageTypeMap.put(InformationResponseMessage.MESSAGE_TYPE, InformationResponseMessage.class);
        messageTypeMap.put(PanelConfigurationResponseMessage.MESSAGE_TYPE, PanelConfigurationResponseMessage.class);
    }

    /**
     * The {@link ItemType} enumerates the items that can be read and potentially also toggled.
     *
     * @author CarlÖnnheim
     *
     */
    public enum ItemType {
        // @formatter:off
        PUMP             ((byte) 0x04, BalboaProtocol.MAX_PUMPS),
        LIGHT            ((byte) 0x11, BalboaProtocol.MAX_LIGHTS),
        AUX              ((byte) 0x16, BalboaProtocol.MAX_AUX),
        BLOWER           ((byte) 0x0c, 1),
        MISTER           ((byte) 0x0e, 1),
        TEMPERATURE_RANGE((byte) 0x50, 1),
        HEAT_MODE        ((byte) 0x51, 1),
        HOLD_MODE        ((byte) 0x3C, 1),
        // Read only (address is zero)
        PRIMING          ((byte) 0x00, 1),
        HEATER           ((byte) 0x00, 1),
        CIRCULATION      ((byte) 0x00, 1);
        // @formatter:on

        private final byte address;
        public final int count;

        private ItemType(byte address, int count) {
            this.address = address;
            this.count = count;
        }
    }

    // All messages provide a message type code
    static private interface Message {
        public int getMessageType();
    }

    /**
     * Messages wich can be sent the unit implement this message.
     *
     * @author CarlÖnnheim
     *
     */
    static protected interface Outbound extends Message {
        /**
         * Provides the payload of the message instance.
         *
         * @return byte buffer containing the payload
         */
        public byte[] getPayload();
    }

    /**
     * The {@link ConfigurationRequestMessage} messages are sent to query the device for its configuration.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class ConfigurationRequestMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf04;
        static final byte[] payload = new byte[0];

        @Override
        public byte[] getPayload() {
            return payload;
        }
    }

    /**
     * The {@link SettingsRequestMessage} messages are sent to query the device for its settings.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class SettingsRequestMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf22;

        // Enumerate the different settings types we can request
        public enum SettingsType {
            // @formatter:off
            PANEL        (new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x01 }),
            FILTER_CYCLES(new byte[] { (byte) 0x01, (byte) 0x00, (byte) 0x00 }),
            INFORMATION  (new byte[] { (byte) 0x02, (byte) 0x00, (byte) 0x00 }),
            PREFERENCES  (new byte[] { (byte) 0x08, (byte) 0x00, (byte) 0x00 }),
            // When called with Fault Log, grab the last entry.
            FAULT_LOG    (new byte[] { (byte) 0x20, (byte) 0xFF, (byte) 0x00 });
            // @formatter:on

            private final byte[] payload;

            private SettingsType(byte[] payload) {
                this.payload = payload;
            }
        }

        private SettingsType setting;

        /**
         * Construct a {@link SettingsRequestMessage} for the given SettingType.
         *
         * @param setting the SettingType to create a message for.
         */
        public SettingsRequestMessage(SettingsType setting) {
            this.setting = setting;
        }

        @Override
        public byte[] getPayload() {
            return setting.payload;
        }
    }

    /**
     * The {@link ToggleMessage} messages are sent to alter the state of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class ToggleMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf11;
        private ItemType type;
        private int index;

        /**
         * Construct a {@link ToggleMessage} for the given ToggleType and index.
         *
         * @param type the ToggleMessage to create a message for.
         * @param index the index to create a message for.
         */
        public ToggleMessage(ItemType type, int index) {
            // Make sure we are not trying to toggle a read only state
            if (type.address == 0x00) {
                throw new IllegalArgumentException("Attempt to toggle a read only state");
            }
            // Make sure it does not go out of bounds
            if (index < 0 || index >= type.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }
            this.type = type;
            this.index = index;
        }

        /**
         * Provide the payload. First byte is the item address + index, second byte is always zero
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { (byte) (type.address + index), (byte) 0x00 };
        }
    }

    /**
     * The {@link SetTemperatureMessage} messages are sent to change the target temperature of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class SetTemperatureMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf20;
        private byte targetTemperature;

        /**
         * Construct a {@link SetTemperatureMessage} for the given temperature.
         *
         * @param targetTemperature The temperature to set
         * @param celcius Indicates if the temperature is expressed in celcius (otherwise fahrenheit)
         * @param highRange Indicates if the temperature is in the high range (otherwise low range)
         */
        public SetTemperatureMessage(double targetTemperature, boolean celcius, boolean highRange) {
            // The limits and multiplier
            double low, high, multiplier;
            if (celcius) {
                multiplier = 2;
                if (highRange) {
                    low = 26.5;
                    high = 40;
                } else {
                    low = 10;
                    high = 26;
                }
            } else {
                multiplier = 1;
                if (highRange) {
                    low = 79;
                    high = 104;
                } else {
                    low = 50;
                    high = 80;
                }
            }

            // Set the temperature
            if (targetTemperature < low) {
                this.targetTemperature = (byte) (low * multiplier);
            } else if (targetTemperature > high) {
                this.targetTemperature = (byte) (high * multiplier);
            } else {
                this.targetTemperature = (byte) (targetTemperature * multiplier);
            }
        }

        /**
         * Provide the payload. One byte with the temperature
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { targetTemperature };
        }
    }

    /**
     * The {@link SetTemperatureScaleMessage} messages are sent to set the temperature scale of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class SetTemperatureScaleMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf27;
        private byte scale;

        /**
         * Construct a {@link SetTemperatureScaleMessage}.
         *
         * @param celcius Indicates if the temperature is expressed in celcius (otherwise fahrenheit)
         *
         */
        public SetTemperatureScaleMessage(boolean celcius) {
            scale = (byte) (celcius ? 0x01 : 0x00);
        }

        /**
         * Provide the payload. 0x01 followed by the desired scale
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { (byte) 0x01, scale };
        }
    }

    /**
     * The {@link SetTimeMessage} messages are sent to set the time of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class SetTimeMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf21;
        private byte hour;
        private byte minute;

        /**
         * Construct a {@link SetTimeMessage}.
         *
         * @param hour The hour - always in 24h format
         * @param minute The minute
         * @param display24h Show time in 24h format
         *
         */
        public SetTimeMessage(int hour, int minute, boolean display24h) {
            // Store the hour
            if (hour < 0) {
                this.hour = 0;
            } else if (hour > 23) {
                this.hour = 23;
            } else {
                this.hour = (byte) hour;
            }

            // Store the minute
            if (minute < 0) {
                this.minute = 0;
            } else if (minute > 59) {
                this.minute = 59;
            } else {
                this.minute = (byte) minute;
            }

            // Set the high bit if using 24h time
            if (display24h) {
                this.hour |= 0x80;
            }
        }

        /**
         * Provide the payload. Hour followed by minute
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { hour, minute };
        }
    }

    /**
     * Messages wich can be received from the unit implement this message.
     *
     * @author CarlÖnnheim
     *
     */
    static protected interface Inbound extends Message {
    }

    /**
     * The {@link StatusUpdateMessage} messages are sent repeatedly by the control unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class StatusUpdateMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0xffaf13;
        public static final int MESSAGE_LENGTH = 34;

        private byte[] pumps = new byte[BalboaProtocol.MAX_PUMPS];
        private byte[] lights = new byte[BalboaProtocol.MAX_LIGHTS];
        private byte blower;
        private boolean[] aux = new boolean[BalboaProtocol.MAX_AUX];
        private boolean celcius, time24h, temperatureHighRange, priming, mister, circulation;
        byte timeHour, timeMinute;
        double currentTemperature, targetTemperature;
        byte readyState, heatState, filterState;

        /**
         * Instantiates a {@link StatusUpdateMessage} from a raw data buffer
         *
         * @param buffer The raw data buffer to parse the message from
         */
        public StatusUpdateMessage(byte[] buffer) {
            // Marks which bits are used so changes on the unknown bits can be traced
            // @formatter:off
            final byte[] unknown = {
                    //     0                  1                  2                  3                  4                  5                  6                  7
                    (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b11111111, (byte) 0b11111110, (byte) 0b00000000,
                    //     8                  9                  10                 11                 12                 13                 14                 15
                    (byte) 0b00000000, (byte) 0b00000000, (byte) 0b11111100, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11110000, (byte) 0b11001011,
                    //     16                 17                 18                 19                 20                 21                 22                 23
                    (byte) 0b00000000, (byte) 0b00111100, (byte) 0b11110001, (byte) 0b11110000, (byte) 0b11100110, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111,
                    //     24                 25                 26                 27                 28                 29                 30                 31
                    (byte) 0b11111111, (byte) 0b00000000, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111,
                    //     32,                33
                    (byte) 0b00000000, (byte) 0b00000000
                    };
            // @formatter:on

            // Determine the temperature scale first (since used on other items)
            celcius = (buffer[14] & 0x01) != 0;

            // Byte 0: start mark
            // Byte 1: length
            // Byte 2-4: message type
            // Byte 5: hold mode in 0x05?
            // Byte 6: Bit 0: priming
            priming = (buffer[6] & 0x01) != 0;
            // Byte 7: Current Temperature
            currentTemperature = buffer[7] * (celcius ? 0.5 : 1.0);
            // Byte 8: Time Hour
            timeHour = buffer[8];
            // Byte 9: Time Minute
            timeMinute = buffer[9];
            // Byte 10: Bits 0 and 1: Ready State (0 = Ready, 1 = Rest, 3 = Ready in rest)
            readyState = (byte) (buffer[10] & 0x03);
            // Byte 11: unknown
            // Byte 12: unknown
            // Byte 13: unknown
            // Byte 14: Bit 0: Celcius (otherwise Fahrenheit - determined above), 1: 24h-clock, 2-3: Filter Mode
            time24h = (buffer[14] & 0x02) != 0;
            filterState = (byte) ((buffer[14] >> 2) & 0x03);
            // Byte 15: Bit 0-1: unknown, 2: high range (otherwise low range), 3: unknown,
            // 4-5: heatState (off, low, high, ??), 6-7: unknown
            temperatureHighRange = (buffer[15] & 0x04) != 0;
            heatState = (byte) ((buffer[15] >> 4) & 0x03);
            // Byte 16-17: Pump states. 2 bits per pump like so: P3P2P1P0 P5xxxxP4 (Byte16 Byte17).
            // Each encodes off,low,high,??. 1-speed pumps only off and high.
            for (int i = 0; i < BalboaProtocol.MAX_PUMPS; i++) {
                pumps[i] = (byte) ((buffer[16 + i / 4] >> ((i % 4) * 2)) & 0x03);
            }
            // Byte 18: Bit 0 unknown, Bit 1 circulation, Bits 2-3 blower (off, low, medium, high)
            circulation = (buffer[18] & 0x02) != 0;
            blower = (byte) ((buffer[18] >> 2) & 0x03);
            // Byte 19: Lights like so xxxxL1L0. (off, low, medium, high)
            for (int i = 0; i < BalboaProtocol.MAX_LIGHTS; i++) {
                lights[i] = (byte) ((buffer[19] >> (i * 2)) & 0x03);
            }
            // Byte 20: Bit 0: mister, bit 1-2: unkown, bit 3-4: aux1/2: bit 5-7: unknown
            mister = (buffer[20] & 0x01) != 0;
            for (int i = 0; i < BalboaProtocol.MAX_AUX; i++) {
                aux[i] = (buffer[20] & (0x08 << i)) != 0;
            }
            // Byte 21: unknown
            // Byte 22: unknown
            // Byte 23: unknown
            // Byte 24: unknown
            // Byte 25: target temperature
            targetTemperature = buffer[25] * (celcius ? 0.5 : 1.0);
            // Byte 26: unknown
            // Byte 27: unknown
            // Byte 28: unknown
            // Byte 29: unknown
            // Byte 30: unknown
            // Byte 31: unknown
            // Byte 32: crc
            // Byte 33: end mark

            // @formatter:off
            logger.trace(
                    "Status update received {}:{}"
                            + "\n"
                            + "\nPRI: {}\tCEL: {}\t24H: {}\tHGH: {}\tMIS: {}\tCIR: {}"
                            + "\nBLW: {}\t\tRST: {}\t\tFIL: {}\t\tHEA: {}"
                            + "\nCUR: {}\tTGT: {}"
                            + "\n"
                            + "\nPUM: {}"
                            + "\nLGT: {}"
                            + "\nAUX: {}"
                            + "\n",
                    timeHour, timeMinute
                    , priming, celcius, time24h, temperatureHighRange, mister, circulation
                    , blower, readyState, filterState, heatState
                    , currentTemperature, targetTemperature
                    , pumps
                    , lights
                    , aux);
            // @formatter:on

            traceUnknown(buffer, unknown);
        }

        /**
         * Returns the state of a one- or two-state togglable item.
         *
         * @param item type of the togglable. HEAT and HOLD modes are not supported
         * @param index the index to return (PUMP, AUX and LIGHTS)
         * @return
         */
        public byte getItem(ItemType item, int index) {

            switch (item) {
                case PUMP:
                    if (index < 0 || index > BalboaProtocol.MAX_PUMPS) {
                        return 0;
                    } else {
                        return pumps[index];
                    }
                case AUX:
                    if (index < 0 || index > BalboaProtocol.MAX_AUX) {
                        return 0;
                    } else {
                        return (byte) (aux[index] ? 0x01 : 0x00);
                    }
                case LIGHT:
                    if (index < 0 || index > BalboaProtocol.MAX_LIGHTS) {
                        return 0;
                    } else {
                        return lights[index];
                    }
                case BLOWER:
                    return blower;
                case MISTER:
                    return (byte) (mister ? 0x01 : 0x00);
                case TEMPERATURE_RANGE:
                    return (byte) (temperatureHighRange ? 0x01 : 0x00);
                case CIRCULATION:
                    return (byte) (circulation ? 0x01 : 0x00);
                case HEATER:
                    return heatState;
                case PRIMING:
                    return (byte) (priming ? 0x01 : 0x00);

                // Not yet implemented
                case HEAT_MODE:
                    return 0;
                case HOLD_MODE:
                    return 0;
                default:
                    return 0;
            }
        }

        /**
         * Get the heat state
         *
         * @return
         */
        public byte getHeatState() {
            return heatState;
        }

        /**
         * Get the ready state
         *
         * @return
         */
        public byte getReadyState() {
            return readyState;
        }

        /**
         * Get whether the display is set to celcius
         *
         * @return
         */
        public boolean getCelciusDisplay() {
            return celcius;
        }

        /**
         * Get the filter state
         *
         * @return
         */
        public byte getFilterState() {
            return filterState;
        }

        /**
         * Get the temperatures
         *
         * @return
         */
        public double getTemperature(boolean target) {
            return target ? targetTemperature : currentTemperature;
        }
    }

    /**
     * The {@link InformationResponseMessage} messages are sent in response to a corresponding
     * {@link SettingsRequestMessage}
     * messages.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class InformationResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf24;
        public static final int MESSAGE_LENGTH = 28;

        public InformationResponseMessage(byte[] buffer) {
            logger.trace("Information Response received");
        }

    }

    /**
     * The {@link PanelConfigurationResponseMessage} messages are sent in response to a corresponding
     * {@link SettingsRequestMessage}
     * messages.
     *
     * @author Carl Önnheim - Initial contribution
     */
    static public class PanelConfigurationResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf2e;
        public static final int MESSAGE_LENGTH = 13;

        private byte[] pumps = new byte[BalboaProtocol.MAX_PUMPS];
        private byte[] lights = new byte[BalboaProtocol.MAX_LIGHTS];
        private boolean[] aux = new boolean[BalboaProtocol.MAX_AUX];
        private byte mister;
        private byte circulation;
        private byte blower;

        public PanelConfigurationResponseMessage(byte[] buffer) {

            // Determine the pump configuration
            pumps[0] = (byte) (buffer[5] & 0x03);
            pumps[1] = (byte) ((buffer[5] >> 2) & 0x03);
            pumps[2] = (byte) ((buffer[5] >> 4) & 0x03);
            pumps[3] = (byte) ((buffer[5] >> 6) & 0x03);
            pumps[4] = (byte) ((buffer[6] >> 2) & 0x03);
            pumps[5] = (byte) ((buffer[6] >> 6) & 0x03);
            logger.trace("byte5..6 {} {}: Pumps {}", Integer.toBinaryString(buffer[5] & 0xFF),
                    Integer.toBinaryString(buffer[6] & 0xFF), pumps);

            // Determine the lights configuration.
            lights[0] = (byte) (buffer[7] & 0x03);
            lights[1] = (byte) ((buffer[7] >> 6) & 0x03);
            logger.trace("byte7 {}: Lights {}", Integer.toBinaryString(buffer[7] & 0xFF), lights);

            // Determine the aux and mister configuration.
            aux[0] = (buffer[9] & 0x01) != 0;
            aux[1] = (buffer[9] & 0x02) != 0;
            mister = (byte) ((buffer[9] >> 4) & 0x03);
            logger.trace("byte9 {}: AUX {}, mister {}", Integer.toBinaryString(buffer[9] & 0xFF), aux, mister);

            // Determine circulation and blower
            blower = (byte) (buffer[8] & 0x03);
            circulation = (byte) ((buffer[8] >> 6) & 0x03);
            logger.trace("byte8 {}: circulation {}, blower {}", Integer.toBinaryString(buffer[8] & 0xFF), circulation,
                    blower);

        }

        /**
         * Gets the configuration of the pump at index i
         *
         * @param i
         * @return the configuration byte
         */
        public byte getPump(int i) {
            if (i < 0 || i > BalboaProtocol.MAX_PUMPS) {
                return 0;
            } else {
                return pumps[i];
            }
        }

        /**
         * Gets the configuration of the light at index i
         *
         * @param i
         * @return the configuration byte
         */
        public byte getLight(int i) {
            if (i < 0 || i > BalboaProtocol.MAX_LIGHTS) {
                return 0;
            } else {
                return lights[i];
            }
        }

        /**
         * Gets the configuration of the aux at index i
         *
         * @param i
         * @return the existence of the aux item
         */
        public boolean getAux(int i) {
            if (i < 0 || i > BalboaProtocol.MAX_AUX) {
                return false;
            } else {
                return aux[i];
            }
        }

        /**
         * Gets the blower configuration
         *
         * @return the blower configuration
         */
        public byte getBlower() {
            return blower;
        }

        /**
         * Gets the circulation configuration
         *
         * @return the circulation configuration
         */
        public byte getCirculation() {
            return circulation;
        }

        /**
         * Gets the mister configuration
         *
         * @return the mister configuration
         */
        public byte getMister() {
            return mister;
        }

        // Show some samples (this is to be moved to a test suite or removed altogether)
        /*
         * static {
         * showSample("7e0b0abf2e1500019000003c7e", "");
         * showSample("7e0b0abf2e1a0001900000ac7e",
         * "1 light, pump1 & 2 are 2-speed pumps, and pump 3 is only 1-speed, no mister or blower");
         * showSample("7e0b0abf2e050001910000c97e", "");
         *
         * }
         *
         * private static void showSample(String config, String description) {
         * logger.trace("Sample: {}", description);
         * new PanelConfigurationResponseMessage(DatatypeConverter.parseHexBinary(config));
         * }
         */
    }

    private BalboaMessage() {
    }

    private BalboaMessage(int messageType, byte[] payload) {
        logger.debug(String.format("Unrecognized Message type 0x%x: %s", messageType,
                DatatypeConverter.printHexBinary(payload)));
    }

    /**
     * Get the Message Type of a {@link BalboaMessage}.
     *
     * @return The message type or zero if not available.
     */
    public int getMessageType() {
        try {
            return this.getClass().getField("MESSAGE_TYPE").getInt(null);
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * Get the expected length of a a {@link BalboaMessage} derived class.
     *
     * @return The expected length of an encoded message
     */
    static public int getMessageLength(Class<? extends BalboaMessage> cls) {
        try {
            return cls.getField("MESSAGE_LENGTH").getInt(null);
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * Get get a {@link BalboaMessage} instance from a buffer.
     *
     * @return A new {@link BalboaMessage} instance.
     */
    static public @Nullable BalboaMessage fromBuffer(byte[] buffer) {

        // Determine the message type (bitmasking 0xFF effectively treats the bytes as unsigned)
        int messageType = (buffer[2] & 0xFF) << 16 | (buffer[3] & 0xFF) << 8 | (buffer[4] & 0xFF);

        if (messageTypeMap.containsKey(messageType)) {
            // Lookup the class which handles the message type
            Class<? extends BalboaMessage> cls = messageTypeMap.get(messageType);

            // Check that the buffer has the appropriate length
            int expectedLength = getMessageLength(cls);
            if (buffer.length != expectedLength) {
                logger.debug("Buffer length {} is not appropriate for a {}, expected {}", buffer.length, cls.getName(),
                        expectedLength);
                return null;
            }

            try {
                return cls.getConstructor(byte[].class).newInstance(buffer);
            } catch (Throwable e) {
                logger.debug("Failed to instantiate {}: {}", cls.getName(), e.getMessage());
                return null;
            }
        } else {
            // Default to the generic message if not known
            return new BalboaMessage(messageType, buffer);
        }
    }

    static HashMap<Integer, byte[]> lastMaskedBuffer = new HashMap<Integer, byte[]>();

    /**
     * Prints trace information about the message in buffer, masked to only show the unknown bits
     *
     * @param buffer
     * @param unknown
     */
    public void traceUnknown(byte[] buffer, byte[] unknown) {
        // No need to prepare the data if it is not going to be traced.
        if (!logger.isTraceEnabled()) {
            return;
        }

        // Consistency check
        if (unknown.length != buffer.length) {
            throw new IllegalArgumentException("Buffer and unknown mask must be the same length");
        }

        // Determine the message type (bitmasking 0xFF effectively treats the bytes as unsigned)
        int messageType = (buffer[2] & 0xFF) << 16 | (buffer[3] & 0xFF) << 8 | (buffer[4] & 0xFF);

        // mask out the known data
        byte[] masked = new byte[buffer.length];
        for (int i = 0; i < buffer.length; i++) {
            masked[i] = (byte) (buffer[i] & unknown[i]);
        }

        // Check if it is a change from the last message of the same type (same length and same checksum)
        byte[] last = lastMaskedBuffer.get(messageType);
        if (last != null && last.length == masked.length) {
            int i = 0;
            for (i = 0; i < masked.length; i++) {
                if (masked[i] != last[i]) {
                    break;
                }
            }
            if (i >= masked.length) {
                return;
            }
        }

        // Store it for use in the next pass
        lastMaskedBuffer.put(messageType, masked);

        // Print the masked buffer as a whole
        String s = String.format("Changes on unknown bits: %s\n", DatatypeConverter.printHexBinary(masked));
        // A heading line
        s = s.concat("          0        1        2        3        4        5        6        7");
        // Print each byte
        String s1 = new String("");
        for (int i = 0; i < masked.length; i++) {
            // Break line every 8 bytes
            if (i % 8 == 0) {
                s = s.concat(s1).concat(String.format("\n%1d0 ", i / 8));
                s1 = "\n   ";
            }
            // Print the byte (blank for known positions)
            for (int b = 7; b >= 0; b--) {
                s = s.concat((unknown[i] & (0x01 << b)) == 0 ? " " : (masked[i] & (0x01 << b)) == 0 ? "0" : "1");
                s1 = s1.concat(last == null ? " " : (last[i] & (0x01 << b)) == (masked[i] & (0x01 << b)) ? " " : "^");
            }
            s = s.concat(" ");
            s1 = s1.concat(" ");
        }
        s = s.concat(s1);
        logger.trace(s);
    }
}
