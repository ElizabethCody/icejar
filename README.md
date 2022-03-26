# icejar
This is software for extending a Mumble server using
[ZeroC Ice](https://zeroc.com/products/ice) and Java. For each connection to a
Mumble server, a configuration file is provided which defines the details of the
connection, the modules to enable, and an optional configuration for each
enabled module.

# Usage
There are 2 accepted command line arguments.

* `-s path/to/server/config/directory` (defaults to `./servers`)
* `-m path/to/module/directory` (defaults to `./modules`)

# Configuration
Server configuration files use the [TOML](https://toml.io) format. Their file
names must end in `.toml`.

The following keys are used:

* `server_id`: Attempt to connect to a virtual server with this ID.

  If `server_name` is defined, the connection will only succeed if the virtual
  server with the given ID also has a `registerName` equal to the value of
  `server_name`.

  If `server_id` is undefined, `server_name` will be used to
  determine the virtual server to which the connection will be made.
  
  By default, the Mumble server creates a single virtual server with an ID
  equal to `1`.

* `server_name`: Attempt to connect to a virtual server with this name. If
  `server_id` is defined, the connection will be established according to the
  description of `server_id`.

    If `server_id` *and* `server_name` are undefined, the connection to the 
    Mumble server will not connect to a specific virtual server as well.

* `ice_args`: Arguments with which the Ice Communicator will be initialized.
  The available arguments are documented by [ZeroC](https://doc.zeroc.com/ice/3.7/properties-and-configuration/command-line-parsing-and-initialization).
  The value should be a list of strings.

* `ice_proxy_string`: The details of the Ice connection. Takes the form
  `"-h HOST -p PORT"` where `HOST` is the hostname or IP address of the Mumble
  server and `PORT` is the port on which Ice is listening.

  To connect to a Mumble using the default Ice settings, set this value to
  `"-h 127.0.0.1 -p 6502"`.

* `ice_secret`: Plaintext secret which must match the value configured on the
  server in order to connect. If the server does not set an Ice secret,
  `ice_secret` can be left undefined. See the
  [Mumble wiki](https://wiki.mumble.info/wiki/Murmur.ini#icesecretread_and_icesecretwrite)
  for more information.
  
* `enabled_modules`: List of module names to enable for this connection. The
  names should be the file names of modules without their extension, i.e. to
  enable the module found at `./modules/foo.jar`, you would add `"foo"` to the
  value of `enabled_modules`.

* `enabled`: Control whether or not the config will be used to establish a
  connection. Set to `true` or `false`. Leaving `enabled` undefined is the same
  as setting it to `true`.

Additionally, each enabled module may also be configured. To do so, create a
table in the server configuration file with the same name as the module you want
to configure. The key-value pairs defined will be passed to the appropriate 
module whenever a connection is established.

## Example configuration:
```toml
# Configuration for connection
server_id = 1

ice_args = []
ice_proxy_string = "-h 127.0.0.1 -p 6502"
ice_secret = "secret"

enabled_modules = [ "test_module", "another_module" ]

# Configuration for `test_module`
[test_module]
foo = "bar"
arbitrary_key = "arbitrary_value"
```

# Modules
Modules are JAR files which contain a class called `Module` which is not in any
named package. This class *must* extend the abstract `Module` defined in
`interfaces/src/main/java/Module.java`.

The abstract `Module` class defines two methods which can be overridden:

* `setup`: This method is called whenever a connection is established and *must*
  be overridden by sub-classes. This method is called with the configuration for
  the module, the interface to the Mumble server, and optionally the interface
  to the virtual server if one was configured. This method may be called
  multiple times for any given module.

* `cleanup`: This method is called only once, when a module is unloaded. It does
  not have to be overridden, but is provided to support modules which require
  some additional clean-up procedure. Callbacks registered using Ice are cleaned
  up automatically and do not require an implementation of this method.

Sub-classes of `Module` *must* provide a constructor which accepts no arguments,
either by keeping the default constructor or explicitly defining its equivalent.

More information is available in the generated documentation for the
`interafces` and `ice` sub-projects. You can generate this documentation by
running `gradle javadoc`.

## Compling Modules
To compile modules, add the JAR file located at
`interfaces/build/libs/MumbleIceInterfaces.jar` to the class path. 