# Usage

This chapter covers configuring a Mumble server for Ice and running Icejar.

## Mumble Server Configuration

Before running Icejar, ensure that the Mumble server's configuration satisfies
the following:

By default, the Mumble server will listen for ice connections on the loopback
interface on port 6502. This can be changed via the following setting in the
Mumble server's INI file:

```ini
ice="tcp -h 127.0.0.1 -p 6502"
```

If this option is not set, Mumble will use the default settings above.

Additionally, Ice should be secured with a passphrase via the following
settings in the Mumble server's INI file:

```ini
icesecretread="YOUR-SECRET"
icesecretwrite="YOUR-SECRET"
```

Choose a strong secret to replace `YOUR-SECRET` and use the same secret for
both values.

Ice uses an unencrypted transport by default and Icejar does not support
Glacier2 (ZeroC's secure transport for Ice). Therefore, it is **strongly**
recommended that you run Icejar on the same machine as the Mumble server(s) to
which it connects. If this is not possible, the connection can be secured with
[WireGuard](https://www.wireguard.com/) or another encrypted tunnel.

## Running Icejar

Before running Icejar, create a directory which contains the following:

* A sub-directory named `servers`.
* A sub-directory named `modules`.
* `MumbleIce.jar` (see [Building](building.md))

Icejar can connect to multiple Mumble servers. The details of a connection to a
Mumble server are written in a TOML file in the `servers` sub-directory.

For example: create a file called `my_server.toml` in the `servers`
sub-directory with the following values:

```toml
ice_host = "127.0.0.1"
ice_port = 6502
ice_secret = "YOUR-SECRET"
```

Replace the values as needed (i.e. replace `YOUR-SECRET` with the actual Ice
secret configured on the Mumble server).

Provided that your mumble server is running and reachable over Ice with the
settings configured in `my_server.toml`, you can now run the following from
the directory containing `MumbleIce.jar`:

```shell
$ java -jar MumbleIce.jar
```

If everything is working properly, you will see the following log output:
```
[INFO] icejar: Starting Icejar.
[INFO] icejar.client.my_server: Connecting...
[INFO] icejar.client.my_server: Connected.
```

## Useful Links

* [Default Mumble server configuration](https://github.com/mumble-voip/mumble/blob/master/auxiliary_files/mumble-server.ini)
* [Mumble Wiki: Ice configuration](https://wiki.mumble.info/wiki/Murmur.ini#ice)
* [Mumble docs: Ice](https://www.mumble.info/documentation/mumble-server/scripting/ice/)
