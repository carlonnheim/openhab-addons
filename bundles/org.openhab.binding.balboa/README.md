# Balboa Binding

This binding supports SPA control units from Balboa.

## Supported Things

The binding supports Balboa SPA Control units with a WIFI module. While there are several different types of control units, it is expected that they all adhere to the same basic protocol. This is however an assumption since the protocol is not publicly available. The protocol binding has so far been tested with the following control unit types:

* BP20100G1

The communication protocol with the control unit also includes mechanisms to determine what capabilities exist. The following table outlines what has been implemented and the testing state (help needed from owners of units with alternative configurations):

## Discovery

Discover is not yet supported for this binding. Manual Thing configuration is required.

## Binding Configuration

The binding does not need any configuration. If you are running this development version and want to help with the interpretation of the underlying protocol, the following log settings are recommended:

* org.openhab.binding.balboa.internal.BalboaMessage: TRACE
* org.openhab.binding.balboa.internal.BalboaProtocol: DEBUG
* org.openhab.binding.balboa.internal.BalboaHandler: DEBUG

## Thing Configuration

Things can be configured in the Paper UI or in a things file looking like this

* The `host` parameter is mandatory. You can give an IP-address or a resolvable hostname.
* The `reconnectInterval` is optional and determines for how long the binding will wait between reconnect attempts (default 30 seconds)
* The `port` is optional and determines which port number the binding will connect to (defaults 4257).

## Channels

The following channels are always present on the thing once connected and configured.

Channel | Type | Description | Implemented Behavior | Tested
---|---|---|---|---
temperature-scale | String | Shows if the temperature scale is Celcius or Fahrenheit. Note that this affects how the unit itself displays temperature, but not how OpenHAB displays it. | Always present, Celcius or Fahrenheit | Tested
temperature-range | String | Shows if the temperature scale is High or Low | Always present, Low or High | Tested
current-temperature | Number:Temperature | Read Only. Shows the current temperature of the unit | Always present | Tested
target-temperature | Number:Temperature | The target temperature | Always present | Tested
heat-mode | String | Valid states are "Ready", "Rest" and "Ready in Rest". Only Ready and Rest can be set. | Always present | Tested
filter-status | String | Read Only. Valid states are "Off", "Filter 1", "Filter 2" and "Filter 1+2" | Always present. | Partially tested 
priming | Contact | Open (active) or Closed (not active) | Always present. | Tested
circulation | Contact | Open (active) or Closed (not active) | Always present. | Tested
heater | Contact | Open (active) or Closed (not active) | Always present. | Tested

The following channels are only present if the unit actually has the corresponding capability. Note that the channel type differs depending on the type of object (one- or two-speed).

Channel | Type | Description | Implemented Behavior | Tested
---|---|---|---|---
pump-X | Switch or String | On/Off or Two-Speed pump number 1-6 | Up to 6 pumps, each of which can be one- or two-speed | 3 one-speed pumps tested
light-X | Switch or String | On/Off or Two-Level light number 1-2 | Up to 2 lights, each of which can be one- or two-levels | Single one-level light tested
aux-X | Switch | On/Off AUX channel 1-2 | Up to 2 AUX channels with simple on/off state | Not tested
blower | Switch or String | On/Off or Two-Level blower | Optional feature, one- or two-speed | One-speed tested
mister | Switch or String | On/Off or Two-Level blower | Optional feature, one- or two-speed | Not tested

## Full Example

*.things:

```
Thing balboa:balboa-ip:hottub "Hot Tub" [ host="ip.of.my.spa", reconnectInterval=30, port=4257 ]
```

*.items

```
String TempScale "Temperature Scale" { channel="balboa:balboa-ip:hottub:temperature-scale" }
String TempRange "Temperature Range" { channel="balboa:balboa-ip:hottub:temperature-range" }
Number:Temperature CurrentTemp "Current Temperature" { channel="balboa:balboa-ip:hottub:current-temperature" }
Number:Temperature TargetTemp "Target Temperature" { channel="balboa:balboa-ip:hottub:target-temperature" }
String HeatMode "Heat Mode" { channel="balboa:balboa-ip:hottub:heat-mode" }
String FilterStatus "Filter Status" { channel="balboa:balboa-ip:hottub:filter-status" }
Contact Priming "Priming" { channel="balboa:balboa-ip:hottub:priming" }
Contact Circulation "Circulation" { channel="balboa:balboa-ip:hottub:circulation" }
Contact Heater "Heater" { channel="balboa:balboa-ip:hottub:heater" }

Switch Pump1 "One Speed Pump" { channel="balboa:balboa-ip:hottub:pump-1" }
String Pump2 "Two Speed Pump" { channel="balboa:balboa-ip:hottub:pump-2" }
Switch Light1 "Lights" { channel="balboa:balboa-ip:hottub:light-1" }
Switch Aux1 "Aux" { channel="balboa:balboa-ip:hottub:aux-1" }
Switch Blower "Blower" { channel="balboa:balboa-ip:hottub:blower" }
Switch Mister "Mister" { channel="balboa:balboa-ip:hottub:mister" }
```


