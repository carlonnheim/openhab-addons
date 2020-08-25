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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link BalboaBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Carl Önnheim - Initial contribution
 */
@NonNullByDefault
public class BalboaBindingConstants {

    protected static final String BINDING_ID = "balboa";

    public static final Integer DEFAULT_PORT = 4257;

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_BALBOA_IP = new ThingTypeUID(BINDING_ID, "balboa-ip");
}
