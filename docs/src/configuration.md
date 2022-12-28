# Configuration

Server configuration files use the [TOML](https://toml.io) format. Their file
names must end in `.toml`. Parsing is done with [toml4j](https://github.com/mwanji/toml4j).

Server configuration can either consist of a single TOML file named
`SERVER NAME.toml` or a directory named `SERVER NAME` containing TOML files.

If a config directory is used, all the TOML files in the directory and any of
its sub-directories will be read and combined into a single config. If the same
setting is defined in multiple files, one file's setting will override the
others. The order in which config files take precedence over each other is
_not_ documented and is subject to change, so defining the same setting in
multiple files should be avoided.

The following keys are used and should be placed in a table called `[server]`:

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

  If unset, this value defaults to the empty list `[]`.

* `ice_host`: The hostname or IP address of the Mumble server. Use `"127.0.0.1"`
  for a local Mumble server.

  If unset, this value defaults to `"127.0.0.1"`.

* `ice_port`: The port on which Ice is listening. Mumble uses port `6502` by
  default.

  If unset, this value defaults to `6502`.

* `ice_secret`: Plaintext secret which must match the value configured on the
  server in order to connect. If the server does not set an Ice secret,
  `ice_secret` can be left undefined. See the
  [Mumble wiki](https://wiki.mumble.info/wiki/Murmur.ini#icesecretread_and_icesecretwrite)
  for more information.

* `callback_host`: The hostname or IP address at which Icejar can be reached
  over TCP. This should only be set if icejar is running on a different
  computer than the Mumble server.

* `callback_port`: The port at which Icejar can be reached over TCP. This
  should only be set if icejar is running on a different computer than the
  Mumble server.

* `enabled_modules`: List of module names to enable for this connection. The
  names should be the file names of modules without their extension, i.e. to
  enable the module found at `./modules/foo.jar`, you would add `"foo"` to the
  value of `enabled_modules`.

  If unset, this value defaults to the empty list `[]`.

* `enabled`: Control whether or not the config will be used to establish a
  connection. Set to `true` or `false`. Leaving `enabled` undefined is the same
  as setting it to `true`.

Additionally, each enabled module may also be configured. To do so, create a
table in the server configuration file with the same name as the module you want
to configure. The key-value pairs defined will be passed to the appropriate 
module whenever a connection is established.
